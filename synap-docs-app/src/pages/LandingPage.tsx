import { Box, Button, VStack, HStack, Heading, Text} from "@chakra-ui/react";
import { useNavigate } from "react-router-dom";

import "../styling/button.css"
import "../styling/shine.css"
import "../styling/text-styles.css"
<link rel="stylesheet" href="https://use.typekit.net/jao8smu.css"></link>

const LandingPage = () => {
    const navigate = useNavigate();

    const textColor = 'white';

  return (
    <Box
        display="flex"
        justifyContent="center"
        alignItems="center"
        height="100vh"
        backgroundSize="cover"
        backgroundPosition="center"
        zIndex={0}
        className="shiny"
        >  
        <Box>
            <VStack textAlign="center" zIndex={1} justifyContent="center" 
            alignItems="center">
                <Heading size="7xl" color={textColor} className="logo">
                    SynapDocs
                </Heading>
                <Text fontSize="2xl" color={textColor} className="sub-logo">
                    Smart AI-Powered Document Search
                </Text>
                <HStack gap={7}>
                    <Button w={180} className="button blue" onClick={() =>
                        window.location.href =
                            "https://synapdocs.com/login"
                        }>
                            <span color={textColor}>Login</span>
                    </Button>
                    <Button w={180} className="button blue" onClick={() =>
                    window.location.href =
                            "https://synapdocs.com/signup"
                        }>
                        <span color={textColor}>Sign Up</span>
                    </Button>
                </HStack>
                <Button w={390} className="button variant" onClick={() => navigate('/chat-page')}>
                <span color={textColor}>Try It As A Guest</span>   
                </Button>
            </VStack>
        </Box>
    </Box>
  );
};

export default LandingPage;