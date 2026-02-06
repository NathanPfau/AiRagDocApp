'use client';
import{ useState, useEffect, useRef, useCallback } from 'react';
import { Flex, Box, Text, Input, Button, VStack, HStack,} from '@chakra-ui/react';
import { toaster } from "@/components/ui/toaster";
import Sidebar from '../components/Sidebar';
import NewChatDialog from "../components/NewChatDialog"
import UploadDoc from '../components/UploadDoc';
import { nanoid } from 'nanoid';
import ReactMarkdown from 'react-markdown';
import "../styling/button.css"
import "../styling/indicator.css"
import "../styling/message-window.css"
import "../styling/shine.css"
import "../styling/text-styles.css"
import { div } from 'framer-motion/client';

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080';

interface ChatItem {
  threadId: string;
  chatName: string;
  chatDocs: string[];
}

interface Message {
  id: string;
  sender: string;
  message: string;
}

interface StreamData {
  token?: string;
  done?: boolean;
  error?: string;
}

export default function ChatPage() {

    const messagesEndRef = useRef<HTMLDivElement | null>(null);
    const textColor = 'white';

    const [chats, setChats] = useState<ChatItem[]>([]);
    const [documents, setDocuments] = useState<string[]>([]);
    const [messages, setMessages] = useState<Message[]>([]);
    const [selectedChat, setSelectedChat] = useState<string | null>(null);
    const [newMessage, setNewMessage] = useState("");
    const [userId, setUserId] = useState<string>("");
    const [selectedDocs, setSelectedDocs] = useState<string[]>([]);
    const [isGuest, setIsGuest] = useState<boolean>(false);
    const [isStreaming, setIsStreaming] = useState<boolean>(false);

    // Refs for streaming to avoid stale closures
    const abortControllerRef = useRef<AbortController | null>(null);
    const streamContentRef = useRef<string>("");
    const tokenQueueRef = useRef<string[]>([]);
    const isProcessingQueueRef = useRef<boolean>(false);

    const handleFileUpload = async (file: File) => {
        if (!userId) {
          console.error("User not initialized");
          throw new Error("User not initialized");
        }
        // Create FormData for file upload
        const formData = new FormData();
        formData.append("file", file, file.name);
        formData.append("user_id", userId);
        formData.append("source", file.name);

        try {
          const response = await fetch(`${API_BASE_URL}/chat-page/documents`, {
            method: "POST",
            body: formData,
          });
          if (response.ok) {
            // On success, update your documents list
            const newDoc = file.name;
            setDocuments(prev => [...prev, newDoc]);
          } else {
            // Log the error and then throw so the promise rejects
            const errorText = await response.text();
            console.error("Upload failed", errorText);
            throw new Error("Upload failed: " + errorText);
          }
        } catch (error: unknown) {
          if (error instanceof Error) {
            console.error("Error uploading file:", error.message);
            throw error;
          } else {
            console.error("Error uploading file:", error);
            throw new Error("Unknown error uploading file");
          }
        }
      };

    useEffect(() => {
        fetch(`${API_BASE_URL}/chat-page/session-init`)
        .then((res) => res.json())
        .then((data) => {
            setUserId(data.userId);
            setDocuments(data.documents || []);
            setChats(data.chats || []);
            setIsGuest(data.isGuest);
        })
        .catch((err) => console.error("Error fetching session:", err));
    }, []);

    useEffect(() => {
        // Reset streaming state when switching chats
        tokenQueueRef.current = [];
        isProcessingQueueRef.current = false;
        streamContentRef.current = '';

        if (selectedChat && userId) {
            fetch(`${API_BASE_URL}/chat-page/messages?thread_id=${selectedChat}&user_id=${userId}`)
                .then((res) => res.json())
                .then((data) => {
                    // Map messages to include IDs for existing messages
                    const messagesWithIds = Array.isArray(data.messages)
                        ? data.messages.map((msg: { sender: string; message: string }, idx: number) => ({
                            ...msg,
                            id: `existing-${idx}-${nanoid(6)}`
                          }))
                        : [];
                    setMessages(messagesWithIds);
                    if (Array.isArray(data.chatDocs) && data.chatDocs.length > 0) {
                        setSelectedDocs(data.chatDocs);
                    }
                })
                .catch((err) => console.error("Error fetching messages:", err));
        }
    }, [selectedChat, userId]);

    const handleConfirmChat = () => {
        if (selectedDocs.length === 0) return;
        const newThreadId = nanoid();
        const newChatItem: ChatItem = {
            threadId: newThreadId,
            chatName: "New Chat",
            chatDocs: selectedDocs,
        };
        setChats(prevChats => [...prevChats, newChatItem]);
        setSelectedChat(newThreadId);
        // setSelectedDocs([]);
    };


    const handleDeleteChat = (threadId: string) => {
        const chatToDelete = chats.find(chat => chat.threadId === threadId);
        if (!chatToDelete) {
        console.error("Chat not found");
        return;
        }
        fetch(`${API_BASE_URL}/chat-page/chats?userId=${userId}&thread_id=${chatToDelete.threadId}&chatName=${encodeURIComponent(chatToDelete.chatName)}`, {
        method: "DELETE"
        })
        .then(response => {
            if (response.ok) {
            setChats(prevChats => prevChats.filter(chat => chat.threadId !== threadId));
            if (selectedChat === threadId) {
                setSelectedChat(null);
                setMessages([]);
            }
            } else {
            console.error("Failed to delete chat");
            }
        })
        .catch(err => console.error("Error deleting chat:", err));
    };

    const handleDeleteDoc = (docName: string) => {
        fetch(`${API_BASE_URL}/chat-page/documents?user_id=${userId}&doc_name=${encodeURIComponent(docName)}`, {
          method: "DELETE"
        })
        .then(response => {
          if (response.ok) {
            // Remove the document from the global documents list
            setDocuments(prevDocs => prevDocs.filter(doc => doc !== docName));
            // Optionally, also remove it from the selectedDocs list if needed
            setSelectedDocs(prevSelected => prevSelected.filter(doc => doc !== docName));
          } else {
            console.error("Failed to delete document");
          }
        })
        .catch(err => console.error("Error deleting document:", err));
      };

    useEffect(() => {
        messagesEndRef.current?.scrollIntoView({ behavior: "smooth" });
    }, [messages]);

    // Process token queue with delays for smooth typing effect
    const processTokenQueue = useCallback(async (messageId: string) => {
        if (isProcessingQueueRef.current || tokenQueueRef.current.length === 0) {
            return;
        }

        isProcessingQueueRef.current = true;

        // Process queue only while not aborted
        while (
            tokenQueueRef.current.length > 0 &&
            abortControllerRef.current &&
            !abortControllerRef.current.signal.aborted
        ) {
            const token = tokenQueueRef.current.shift();
            if (token) {
                streamContentRef.current += token;

                // Update UI with current streamed content
                setMessages(prev =>
                    prev.map(msg =>
                        msg.id === messageId
                            ? { ...msg, message: streamContentRef.current }
                            : msg
                    )
                );

                // Small delay between tokens for typing effect (15ms feels smooth)
                await new Promise(resolve => setTimeout(resolve, 15));
            }
        }

        isProcessingQueueRef.current = false;
    }, []);

    const stopStreaming = useCallback(() => {
        if (abortControllerRef.current) {
            abortControllerRef.current.abort();
            abortControllerRef.current = null;
        }
        tokenQueueRef.current = [];
        isProcessingQueueRef.current = false;
        setIsStreaming(false);
    }, []);

    const handleSend = async () => {
        if (!newMessage.trim() || !selectedChat || !userId) return;

        // Prevent sending while already streaming
        if (isStreaming) return;

        // Abort any existing fetch request
        if (abortControllerRef.current) {
            abortControllerRef.current.abort();
        }

        // Generate unique IDs for messages
        const userMessageId = `user-${nanoid(10)}`;
        const aiMessageId = `ai-${nanoid(10)}`;

        // Check if this is the first message in the chat
        const isFirstMessage = messages.length === 0;

        if (isFirstMessage) {
            const currentChat = chats.find(chat => chat.threadId === selectedChat);
            if (currentChat != null) {
                try {
                    // Update chat name immutably
                    setChats(prevChats => prevChats.map(chat =>
                        chat.threadId === selectedChat
                            ? { ...chat, chatName: newMessage }
                            : chat
                    ));

                    const url = new URL(`${API_BASE_URL}/chat-page/chats`);
                    url.searchParams.append("user_id", userId);
                    url.searchParams.append("thread_id", selectedChat);
                    url.searchParams.append("chat_name", newMessage);
                    // Include selected document names if any
                    selectedDocs.forEach(doc => url.searchParams.append("document_names", doc));

                    await fetch(url.toString(), { method: "POST" });
                } catch (error) {
                    console.error("Error sending new chat name:", error);
                    return;
                }
            }
        }

        const query = newMessage;

        // Add user message to UI
        setMessages(prev => [...prev, { id: userMessageId, sender: "user", message: query }]);

        // Add empty AI message placeholder (will be filled progressively)
        setMessages(prev => [...prev, { id: aiMessageId, sender: "ai", message: "" }]);

        setNewMessage("");
        setIsStreaming(true);

        // Reset streaming refs
        streamContentRef.current = "";
        tokenQueueRef.current = [];
        isProcessingQueueRef.current = false;

        // Set timeout for stream (5 minutes)
        const STREAM_TIMEOUT_MS = 300000;
        let timeoutId: ReturnType<typeof setTimeout> | undefined;

        try {
            // Create new AbortController for this request
            abortControllerRef.current = new AbortController();

            timeoutId = setTimeout(() => {
                if (abortControllerRef.current) {
                    console.error('Stream timeout after 5 minutes');
                    abortControllerRef.current.abort();
                    toaster.create({
                        title: "Request timed out. Please try again.",
                        type: "error",
                        placement: "bottom-end",
                    });
                }
            }, STREAM_TIMEOUT_MS);

            const response = await fetch(`${API_BASE_URL}/chat-page/ask-stream`, {
                method: "POST",
                headers: {
                    "Content-Type": "application/json",
                },
                body: JSON.stringify({
                    thread_id: selectedChat,
                    query: query,
                    user_id: userId,
                    document_names: selectedDocs
                }),
                signal: abortControllerRef.current.signal
            });

            if (!response.ok) {
                throw new Error("Failed to get AI response");
            }

            if (!response.body) {
                throw new Error("No response body for streaming");
            }

            // Use Server-Sent Events (SSE) streaming
            const reader = response.body.getReader();
            const decoder = new TextDecoder();

            while (true) {
                const { value, done } = await reader.read();

                if (done) break;

                const chunk = decoder.decode(value, { stream: true });
                const lines = chunk.split('\n');

                for (const line of lines) {
                    if (line.startsWith('data: ')) {
                        try {
                            const data: StreamData = JSON.parse(line.slice(6));

                            if (data.token) {
                                // Queue the token for processing
                                tokenQueueRef.current.push(data.token);

                                // Start processing queue if not already processing
                                if (!isProcessingQueueRef.current) {
                                    processTokenQueue(aiMessageId).catch(err => {
                                        console.error('Error processing token queue:', err);
                                    });
                                }
                            }

                            if (data.done) {
                                // Wait for queue to finish processing before marking done
                                while (
                                    (tokenQueueRef.current.length > 0 || isProcessingQueueRef.current) &&
                                    abortControllerRef.current &&
                                    !abortControllerRef.current.signal.aborted
                                ) {
                                    await new Promise(resolve => setTimeout(resolve, 50));
                                }

                                // Stream completed successfully - force final update
                                setMessages(prev =>
                                    prev.map(msg =>
                                        msg.id === aiMessageId
                                            ? { ...msg, message: streamContentRef.current }
                                            : msg
                                    )
                                );
                                if (timeoutId) clearTimeout(timeoutId);
                                setIsStreaming(false);
                                abortControllerRef.current = null;
                                return;
                            }

                            if (data.error) {
                                // Error occurred during streaming
                                console.error('Streaming error:', data.error);
                                if (timeoutId) clearTimeout(timeoutId);
                                throw new Error(data.error);
                            }
                        } catch (parseErr) {
                            console.warn('Failed to parse SSE data:', line, parseErr);
                        }
                    }
                }
            }

            if (timeoutId) clearTimeout(timeoutId);
            setIsStreaming(false);
        } catch (error: unknown) {
            // Clear timeout on error
            if (timeoutId) clearTimeout(timeoutId);

            // Only handle error if it wasn't an abort
            if (error instanceof Error && error.name !== 'AbortError') {
                console.error("Error sending message:", error);

                // Remove the AI message placeholder on error
                setMessages(prev => prev.filter(msg => msg.id !== aiMessageId));

                // Display a toast notification to the user
                toaster.create({
                    title: "Error sending message",
                    type: "error",
                    placement: "bottom-end",
                });
            }

            setIsStreaming(false);
            abortControllerRef.current = null;
        }
    };

    // Cleanup on unmount
    useEffect(() => {
        return () => {
            if (abortControllerRef.current) {
                abortControllerRef.current.abort();
            }
        };
    }, []);

    return (
        <Flex
            h="100vh"
            backgroundColor={"#191919"}
            color={textColor}
            display="flex"
            justifyContent="center"
            alignItems="center"
            backgroundSize="cover"
        >
            <Sidebar
                chats={chats}
                documents={documents}
                onSelectChat={(threadId) => {
                    // Only update if the selected thread is different from the current one
                    if (selectedChat !== threadId) {
                      setSelectedChat(threadId);
                      setMessages([]);
                      // Find the chat item based on the threadId
                      const selectedChatItem = chats.find((chat) => chat.threadId === threadId);
                      if (selectedChatItem) {
                        // Filter the chat_docs so that only docs still in the global documents list are included
                        const validDocs = selectedChatItem.chatDocs.filter((doc) =>
                          documents.includes(doc)
                        );
                        setSelectedDocs(validDocs);
                      } else {
                        setSelectedDocs([]);
                      }
                    }
                  }}
                onDeleteChat={handleDeleteChat}
                onDeleteDoc={handleDeleteDoc}
                selectedDocs={selectedDocs}
                setSelectedDocs={setSelectedDocs}
                handleConfirmChat={handleConfirmChat}
                onUpload={handleFileUpload}
                isGuest={isGuest}
            />
            <Box flex="1" color={textColor} justifyContent={"center"} alignItems={"center"} >
                {!selectedChat ? (
                <Flex h="100vh" alignItems="center" justifyContent="center" mx="auto">
                    <VStack gap={4}>
                    <Text fontSize="4xl" color={textColor} className='sub-logo'>
                        SynapDocs Here To Help
                    </Text>
                    <HStack>
                        <NewChatDialog
                            documents={documents}
                            selectedDocs={selectedDocs}
                            setSelectedDocs={setSelectedDocs}
                            handleConfirmChat={handleConfirmChat}
                            textColor={textColor}
                            buttonClass={"button variant"}
                        />
                        <UploadDoc buttonClass='button variant' onUpload={handleFileUpload} />
                    </HStack>
                    </VStack>
                </Flex>
                ) : (
                    <Flex
                    direction="column"
                    maxW="1400px"
                    h="97vh"
                    mx="auto"
                    my="auto"
                    justifyContent="center"
                    alignItems="center"
                    className='message-window'
                    borderRadius={20}
                >
                    <Box
                    flex="1"
                    overflowY="auto"
                    p={4}
                    borderRadius="md"
                    width="100%"
                    h="100%"
                    className='messages'
                    >
                    {messages.map((msg) => (
                        <Flex key={msg.id} justifyContent={msg.sender === "user" ? "flex-end" : "flex-start"} mb={4}>
                            <Box
                            className={msg.sender === "user" ? "message-gleam" : "message-gleam ai"}
                                                      >
                            {msg.sender === "ai" && msg.message === "" ? (
                                <HStack as={div} className='typing-indicator' h={"20px"}>
                                <span></span>
                                <span></span>
                                <span></span>
                                </HStack>
                            ) : (
                                <ReactMarkdown>{msg.message}</ReactMarkdown>
                            )}
                            </Box>
                        </Flex>
                        ))}
                    <div ref={messagesEndRef} />
                    </Box>
                    <Flex mt={4} width="100%" alignItems="center">
                    <Input
                        value={newMessage}
                        onChange={(e)=> setNewMessage(e.target.value)}
                            onKeyDown={(e)=> {
                                if (e.key === 'Enter' && newMessage.trim() !== "" && !isStreaming) {
                                    handleSend();
                                }
                            }}
                        placeholder="Message SynapDocs"
                        flex="1"
                        mr={2}
                        color={"black"}
                        borderRadius={30}
                        size="lg"
                        bg={"white"}
                        className='messages'
                        disabled={isStreaming}
                    />
                    <Button
                        onClick={isStreaming ? stopStreaming : handleSend}
                        className='button blue'
                        disabled={!isStreaming && !newMessage.trim()}
                    >
                        <span color={textColor}>{isStreaming ? "Cancel" : "Send"}</span>
                    </Button>
                    </Flex>
                </Flex>
                )}
            </Box>
        </Flex>
    );
}
