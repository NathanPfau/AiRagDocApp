'use client';

import{ useState, useEffect, useRef } from 'react';
import { Flex, Box, Text, Input, Button, VStack,HStack,} from '@chakra-ui/react';
import { toaster } from "@/components/ui/toaster";
import Sidebar from '../components/Sidebar';
import NewChatDialog from "../components/NewChatDialog"
import UploadDoc from '../components/UploadDoc';
import { nanoid } from 'nanoid';
import ReactMarkdown from 'react-markdown';
import "../styling/button.css"
import "../styling/glass.css"
import "../styling/global.css"
import "../styling/indicator.css"
import { div } from 'framer-motion/client';

interface ChatItem {
  threadId: string;
  chatName: string;
  chatDocs: string[];
}

interface Message {
  sender: string;
  message: string;
}

export default function ChatPage() {

    const messagesEndRef = useRef<HTMLDivElement | null>(null);
    const textColor = 'white';
    
    const [chats, setChats] = useState<ChatItem[]>([]);
    const [selectedChat, setSelectedChat] = useState<string | null>(null);

    const [documents, setDocuments] = useState<string[]>([]);
    const [selectedDocs, setSelectedDocs] = useState<string[]>([]);
    
    const [messages, setMessages] = useState<Message[]>([]);
    const [newMessage, setNewMessage] = useState("");

    const [userId, setUserId] = useState<string>("");
    const [isGuest, setIsGuest] = useState<boolean>(false);

    // Get the list of chats and list of docs a user has stored 
    // and check if user is a guest or has an account 
    useEffect(() => {
        fetch("https://synapdocs.com/chat-page/session-init")
        .then((res) => res.json())
        .then((data) => {
            setUserId(data.userId);
            setDocuments(data.documents || []);
            setChats(data.chats || []);
            setIsGuest(data.isGuest);
        })
        .catch((err) => console.error("Error fetching session:", err));
        }, []);

    // When user selects a previous chat get the message chain from the server
    useEffect(() => {
        if (selectedChat && userId) {
        fetch(`https://synapdocs.com/chat-page/messages?thread_id=${selectedChat}&user_id=${userId}`)
            .then((res) => res.json())
            .then((data: Message[]) => {
            setMessages(data);
            })
            .catch((err) => console.error("Error fetching messages:", err));
        }
    }, [selectedChat, userId]);

    useEffect(() => {
        if (selectedChat) {
        setMessages([]);
        }
    }, [selectedChat]);

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
            const response = await fetch("https://synapdocs.com/chat-page/documents", {
                method: "POST",
                body: formData,
            });
            if (response.ok) {
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
        setSelectedDocs([]);
    };


    const handleDeleteChat = async (threadId: string) => {
        try {
            const chatToDelete = chats.find(chat => chat.threadId === threadId);
            if (!chatToDelete) {
                console.error("Chat not found");
                return;
            }

            const response = await fetch(`https://synapdocs.com/chat-page/chats?userId=${userId}&thread_id=${chatToDelete.threadId}&chatName=${encodeURIComponent(chatToDelete.chatName)}`, {
                method: "DELETE"
            });

            if (response.ok) {
                setChats(prevChats => prevChats.filter(chat => chat.threadId !== threadId));
                if (selectedChat === threadId) {
                    setSelectedChat(null);
                    setMessages([]);
                }
            } else {
                console.error("Failed to delete chat");
            }
        } catch (error) {
            console.error("Error deleting chat:", error);
        }
    };

    const handleDeleteDoc = async (docName: string) => {
        try {
            const response = await fetch(`https://synapdocs.com/chat-page/documents?user_id=${userId}&doc_name=${encodeURIComponent(docName)}`, {
                method: "DELETE"
            });

            if (response.ok) {
                setDocuments(prevDocs => prevDocs.filter(doc => doc !== docName));
                setSelectedDocs(prevSelected => prevSelected.filter(doc => doc !== docName));
            } else {
                console.error("Failed to delete document");
            }
        } catch (error) {
            console.error("Error deleting document:", error);
        }
    };

    const handleSend = async () => {
        if (!newMessage.trim() || !selectedChat || !userId) return;

        // If it is the first message sent add the chat info the database and change the chat name
        if(messages.length == 0){
            const currentChat = chats.find(chat => chat.threadId === selectedChat);
            if(currentChat != null){
                try{ 
                    currentChat.chatName = newMessage;

                    const url = new URL("https://synapdocs.com/chat-page/chats");
                    url.searchParams.append("user_id", userId);
                    url.searchParams.append("thread_id", selectedChat);
                    url.searchParams.append("chat_name", newMessage);
                    // Include selected document names if any
                    selectedDocs.forEach(doc => url.searchParams.append("document_names", doc));
    
                    await fetch(url.toString(), { method: "POST" });
                }catch(error){
                    console.error("Error sending new chat name:", error);
                    return;
                }
               
            }
        }
        
        const query = newMessage;
        setMessages(prev => [...prev, { sender: "user", message: query }]);
        setMessages(prev => [...prev, { sender: "ai", message: "WAITING" }]); // Add a waiting message indicator
        setNewMessage("");

        try {
            const url = new URL("https://synapdocs.com/chat-page/ask");
            url.searchParams.append("query", query);
            url.searchParams.append("thread_id", selectedChat);
            url.searchParams.append("user_id", userId);
            selectedDocs.forEach(doc => url.searchParams.append("document_names", doc));

            const response = await fetch(url.toString(), { method: "GET" });

            if (!response.ok) {
                throw new Error("Failed to ask the AI.");
            }

            const aiResponse = await response.text();

            // Remove the previous "WAITING" message if it exists and then append the AI response
            setMessages(prev => {
                const updated = prev.filter(msg => !(msg.sender === "ai" && msg.message === "WAITING"));
                return [...updated, { sender: "ai", message: aiResponse }];
            });

        } catch (error) {
            console.error("Error sending message:", error);
            // Remove the "WAITING" message if it exists
            setMessages(prev => prev.filter(msg => !(msg.sender === "ai" && msg.message === "WAITING")));
            
            // Display a toast notification to the user
            toaster.create({
                title: "Error sending message",
                type: "error",
                placement: "top"
            })
        }
    };

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
            {/* When user first gets to the ChatPage, welcome message and new chat and upload button */}
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
                    // After a chat is selected or a new started message area is shown
                    <Flex 
                    direction="column" 
                    maxW="1400px"
                    h="97vh" 
                    mx="auto" 
                    my="auto" 
                    justifyContent="center" 
                    alignItems="center" 
                    className='glass'
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
                    {messages.map((msg, idx) => (
                        <Flex key={idx} justifyContent={msg.sender === "user" ? "flex-end" : "flex-start"} mb={4}>
                            <Box
                            className={msg.sender === "user" ? "message-style" : "message-style ai"}
                                                      >
                            {/* waithing for a response indicator */}
                            {msg.message === "WAITING" ? (
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
                                if (e.key === 'Enter' && newMessage.trim() !== "") {
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
                    />
                    <Button onClick={handleSend} className='button send'>
                        <span color={textColor}>Send</span>
                    </Button>
                    </Flex>
                </Flex>
                )}
            </Box>
        </Flex>
    );
}
