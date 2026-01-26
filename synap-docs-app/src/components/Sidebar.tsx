'use client';

import React from 'react';
import { Box, Flex, Text, Stack, Icon, IconButton, BoxProps, List, ListItem, Button, Heading,} from '@chakra-ui/react';
import { LuMessageSquareText, LuFileText, LuEllipsis, LuLogOut, LuLogIn } from 'react-icons/lu';
import { AccordionItem, AccordionItemContent, AccordionItemTrigger, AccordionRoot,} from '@/components/ui/accordion';
import { MenuRoot, MenuTrigger, MenuContent, MenuItem,} from '@/components/ui/menu';
import NewChatDialog from "./NewChatDialog"
import UploadDoc from './UploadDoc';

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080';

interface ChatItem {
  threadId: string;
  chatName: string;
  chatDocs: string[];
}

export interface SidebarProps extends BoxProps {
  chats: ChatItem[];
  documents: string[];
  onSelectChat: (threadId: string) => void;
  onDeleteChat: (chatId: string) => void;
  onDeleteDoc: (doc: string) => void;
  selectedDocs: string[];
  setSelectedDocs: React.Dispatch<React.SetStateAction<string[]>>;
  handleConfirmChat: () => void;
  onUpload: (file: File) => Promise<void>;
  isGuest: boolean;
}
const Sidebar: React.FC<SidebarProps> = ({
  chats,
  documents,
  onSelectChat,
  onDeleteChat,
  onDeleteDoc,
  selectedDocs,
  setSelectedDocs,
  handleConfirmChat,
  onUpload,
  isGuest,
}) => {
  // Toggle between light/dark mode
    // const { colorMode, toggleColorMode } = useColorMode();
    const textColor = 'white';
    const handleLogout = () => {
      window.location.href = `${API_BASE_URL}/logout`;
    };

  return (
    <Flex
        direction="column"
        h="97vh" 
        mx="auto" 
        my="auto" 
        w="300px"
        flexShrink={0}
        className='shiny sidebar'
        color={textColor}
        borderRadius={30}
        ml="20px"
    >
      {/* Top Section: Logo / User Info */}
      <Flex
        h="20"
        px={4}
        alignItems="center"
        justifyContent="center"
        borderBottom="1px solid"
        borderColor={"white"}
      >
        <Box lineHeight="1.2">
            <Heading className='logo' size="4xl">SynapDocs</Heading>
        </Box>
      </Flex>

      {/* Accordion: Chats & Docs */}
      <Stack width="full" flex="1" overflowY="auto">
        <AccordionRoot variant="plain" collapsible defaultValue={['chats']} >
          {/* CHATS Accordion */}
          <AccordionItem value="chats">
            <AccordionItemTrigger className='accordion-head'>
              <Icon fontSize="lg" as={LuMessageSquareText} mr={2} />
              Chats
            </AccordionItemTrigger>
            <AccordionItemContent>
              <List.Root variant="plain">
                {/* Start New Chat */}
                <ListItem py={2}>
                  <Flex align="center" w="100%">
                    <NewChatDialog 
                      documents={documents} 
                      selectedDocs={selectedDocs} 
                      setSelectedDocs={setSelectedDocs} 
                      handleConfirmChat={handleConfirmChat}
                      textColor={textColor}
                      buttonClass='button sidebar'
                    />
                  </Flex>
                </ListItem>
                {/* Existing Chats */}
                {chats.length === 0 ? (
                  <Text py={2}>
                    No chats found
                  </Text>
                ) : (
                  chats.map((chat) => (
                    <ListItem
                      key={chat.threadId}
                      py={2}
                      _hover={{ cursor: 'pointer' }}
                    >
                      <Flex align="center" w="100%">
                        <Text onClick={() => onSelectChat(chat.threadId)} truncate>
                          {chat.chatName}
                        </Text>
                        <MenuRoot>
                          <MenuTrigger asChild>
                            <IconButton
                              aria-label="Chat actions"
                              variant="ghost"
                              size="sm"
                              ml="auto"
                              onClick={(e) => e.stopPropagation()}
                            >
                              <LuEllipsis />
                            </IconButton>
                          </MenuTrigger>
                          <MenuContent>
                            <MenuItem
                              value="delete chat"
                              onClick={(e) => {
                                e.stopPropagation();
                                onDeleteChat(chat.threadId);
                              }}
                            >
                              <Text color="red">Delete Chat</Text>
                            </MenuItem>
                          </MenuContent>
                        </MenuRoot>
                      </Flex>
                    </ListItem>
                  ))
                )}
              </List.Root>
            </AccordionItemContent>
          </AccordionItem>

          {/* DOCS Accordion */}
          <AccordionItem value="docs">
            <AccordionItemTrigger className='accordion-head'>
              <Icon fontSize="lg" as={LuFileText} mr={2} />
              Docs
            </AccordionItemTrigger>
            <AccordionItemContent>
              <List.Root variant="plain">
                {/* Upload Doc */}
                <ListItem>
                  <Flex align={"center"}>
                    <UploadDoc buttonClass='button sidebar' onUpload={onUpload} />
                  </Flex>
                </ListItem>
                {/* Existing Documents */}
                {documents.length === 0 ? (
                  <Text py={2}>
                    No documents found
                  </Text>
                ) : (
                  documents.map((doc, idx) => (
                    <ListItem
                      key={idx}
                      py={2}
                    >
                      <Flex align="center" w="100%">
                        <Text truncate>{doc}</Text>
                        <MenuRoot>
                          <MenuTrigger asChild>
                            <IconButton
                              aria-label="Document actions"
                              variant="ghost"
                              size="sm"
                              ml="auto"
                              onClick={(e) => e.stopPropagation()}
                            >
                              <LuEllipsis />
                            </IconButton>
                          </MenuTrigger>
                          <MenuContent>
                            <MenuItem
                              value="delete doc"
                              onClick={(e) => {
                                e.stopPropagation();
                                onDeleteDoc(doc);
                              }}
                            >
                              <Text color="red">Delete Doc</Text>
                            </MenuItem>
                          </MenuContent>
                        </MenuRoot>
                      </Flex>
                    </ListItem>
                  ))
                )}
              </List.Root>
            </AccordionItemContent>
          </AccordionItem>
        </AccordionRoot>
      </Stack>

      <Box
        px={4}
        py={3}
        borderTop="1px solid"
        borderColor={"white"}
        h={"80px"}
      >
        {isGuest ? (

          <Button
          variant="plain"
          w="full"
          justifyContent="flex-start"
          mb={3}
          _hover={{ cursor: 'pointer' }}
          onClick={() => window.location.href =
            `${API_BASE_URL}/login`
          }          
          >
          <LuLogIn style={{ marginRight: '0.5rem' }} />
          Login
          </Button>
        ) : (
          <Button
            variant="plain"
            w="full"
            justifyContent="flex-start"
            mb={3}
            _hover={{ cursor: 'pointer' }}
            onClick={handleLogout}
          >
            <LuLogOut style={{ marginRight: '0.5rem' }} />
            Logout
          </Button>
        )}
      </Box>
    </Flex>
  );
};

export default Sidebar;