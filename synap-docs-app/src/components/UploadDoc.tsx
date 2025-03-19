// UploadDocs.tsx it the button for the uploading pdfs and starts the toast for upload status
import React from 'react';
import { Button, Box, Input } from '@chakra-ui/react';
import { LuUpload } from 'react-icons/lu';
import { toaster } from "@/components/ui/toaster";

interface UploadDocProps {
  onUpload: (file: File) => Promise<void>;
  buttonClass: string;
}

const UploadDoc: React.FC<UploadDocProps> = ({ onUpload, buttonClass }) => {
  const textColor = 'white';
  const fileInputRef = React.useRef<HTMLInputElement>(null);

  const handleFileChange = (event: React.ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0];
    if (file) {
      // Clear the input so the same file can be reselected if needed.
      event.target.value = "";

      const promise = onUpload(file);

      toaster.promise(promise, {
        loading: { title: `Uploading ${file.name}`, description: "Please wait" },
        success: { title: "Successfully uploaded!", description: `${file.name} has been uploaded.` },
        error: { title: "Upload failed", description: "Something went wrong with the upload, try again later" },
      });
    }
  };

  const handleUploadClick = () => {
    fileInputRef.current?.click();
  };

  return (
    <Box>
      <Input 
        type="file" 
        accept=".pdf" 
        ref={fileInputRef} 
        onChange={handleFileChange} 
        display="none" 
      />
      <Button 
        color={textColor} 
        className={buttonClass}
        onClick={handleUploadClick}
      >
        <span><LuUpload color={textColor}/></span>
        <span color={textColor}>Upload PDF</span>
      </Button>
    </Box>
  );
};

export default UploadDoc;


