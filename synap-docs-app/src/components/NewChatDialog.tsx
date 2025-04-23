import React, { useState } from 'react';
import { Button, VStack, Flex, Text, Switch, Dialog, Portal } from '@chakra-ui/react';
import { LuPlus, LuX, LuCheck } from 'react-icons/lu';

interface NewChatDialogProps {
    documents: string[];
    selectedDocs: string[];
    setSelectedDocs: React.Dispatch<React.SetStateAction<string[]>>;
    handleConfirmChat: () => void;
    textColor: string;
    buttonClass: string;
}

const NewChatDialog: React.FC<NewChatDialogProps> = ({ documents, selectedDocs, setSelectedDocs, handleConfirmChat, textColor, buttonClass }) => {
    const [isOpen, setIsOpen] = useState(false);

    const handleStartChat = () => {
        handleConfirmChat();
        setIsOpen(false); // Close the dialog after starting a chat
    };

    return (
        <Dialog.Root size="md" placement="center" motionPreset="slide-in-bottom" scrollBehavior="inside" onInteractOutside={() => setIsOpen(false)} open={isOpen}>
            <Dialog.Trigger asChild>
                <Button className={buttonClass} alignContent={"center"} justifyContent={"center"} onClick={() => setIsOpen(true)}>
                    <span><LuPlus color={textColor} /></span>
                    <span style={{ color: textColor }}>Start New Chat</span>
                </Button>
            </Dialog.Trigger>

            <Portal>
                <Dialog.Backdrop />
                <Dialog.Positioner>
                    <Dialog.Content>
                        <Dialog.Header>
                            <Dialog.Title>Choose Docs to Query</Dialog.Title>
                        </Dialog.Header>
                        <Dialog.Body spaceY="4">
                            {documents.length === 0 ? (
                                <Text>No uploaded docs</Text>
                            ) : (
                                <VStack align="start" gap={3}>
                                    {documents.map((doc, index) => (
                                        <Flex key={index} alignItems="center" justifyContent="space-between" width="100%">
                                            <Text fontSize="sm">{doc}</Text>
                                            <Switch.Root
                                                id={doc}
                                                checked={selectedDocs.includes(doc)}
                                                onCheckedChange={({ checked }) => {
                                                    setSelectedDocs(prev =>
                                                        checked ? [...prev, doc] : prev.filter(d => d !== doc)
                                                    );
                                                }}
                                            >
                                                <Switch.HiddenInput />
                                                <Switch.Control>
                                                    <Switch.Thumb>
                                                        <Switch.ThumbIndicator fallback={<LuX color="black" />}>
                                                            <LuCheck />
                                                        </Switch.ThumbIndicator>
                                                    </Switch.Thumb>
                                                </Switch.Control>
                                            </Switch.Root>
                                        </Flex>
                                    ))}
                                </VStack>
                            )}
                            <Button onClick={handleStartChat} disabled={selectedDocs.length === 0}>
                                Start Chat
                            </Button>
                        </Dialog.Body>
                    </Dialog.Content>
                </Dialog.Positioner>
            </Portal>
        </Dialog.Root>
    );
};

export default NewChatDialog;