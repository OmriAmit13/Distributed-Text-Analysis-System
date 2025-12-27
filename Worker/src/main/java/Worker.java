import software.amazon.awssdk.services.sqs.model.Message;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.UUID;

public class Worker {

    private static final AWS aws = AWS.getInstance();
    private static TextAnalyzer textAnalyzer;

    public static void main(String[] args) {

        // Initialize AWS connections
        System.out.println("Worker starting...");
        aws.getEC2Tags();
        System.out.println("AWS connections initialized.");

        // Initialize the text analyzer
        System.out.println("Initializing Stanford CoreNLP pipeline...");
        textAnalyzer = new TextAnalyzer();
        System.out.println("Text analyzer initialized.");

        // Main processing loop
        System.out.println("Worker entering main processing loop...");
        while (true) {
            try {
                processMessages();
            } catch (Throwable e) {
                // Continue working - don't stop because of an error
                System.err.println("Error in main loop: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    private static void processMessages() {
        List<Message> messages = aws.receiveMessagesFromManager();

        if (messages.isEmpty()) {
            return;
        }

        for (Message message : messages) {
            processMessage(message);
        }
    }

    private static void processMessage(Message message) {
        String body = message.body();
        System.out.println("Processing message: " + body);

        String[] parts = body.split(" ");
        if (parts.length < 3) {
            System.err.println("Invalid message format: " + body);
            aws.deleteMessageFromManager(message);
            return;
        }

        String analysisTypeStr = parts[0];
        String fileUrl = parts[1];
        String appId = parts[2];

        // Convert string to AnalysisType enum
        TextAnalyzer.AnalysisType analysisType;
        try {
            analysisType = TextAnalyzer.AnalysisType.valueOf(analysisTypeStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            System.err.println("Invalid analysis type: " + analysisTypeStr);
            String errorMessage = analysisTypeStr + " " + fileUrl + " ERROR:Invalid_analysis_type " + appId;
            aws.sendMessageToManager(errorMessage);
            aws.deleteMessageFromManager(message);
            return;
        }

        String fileName = analysisTypeStr + "-" + fileUrl.substring(fileUrl.lastIndexOf('/') + 1);
        String taskId = UUID.randomUUID().toString();
        String localInputPath = "input_" + taskId + ".txt";
        String localOutputPath = "output_" + taskId + ".txt";
        String s3OutputKey = "processed/" + appId + "/" + fileName;

        try {
            // Download file from URL to local temp file
            System.out.println("Downloading file from: " + fileUrl);
            downloadFile(fileUrl, localInputPath);
            System.out.println("Download complete: " + localInputPath);

            // Analyze file using TextAnalyzer
            File inputFile = new File(localInputPath);
            File outputFile = new File(localOutputPath);
            
            System.out.println("Analyzing file with type: " + analysisType);
            textAnalyzer.analyzeFile(inputFile, outputFile, analysisType);
            System.out.println("Analysis complete.");

            // Upload result to S3
            System.out.println("Uploading result to S3...");
            String outputPublicUrl = aws.uploadToS3(s3OutputKey, localOutputPath);
            System.out.println("Uploaded result to S3: " + s3OutputKey);

            // Send success message to manager
            String resultMessage = analysisTypeStr + " " + fileUrl + " " + outputPublicUrl + " " + appId;
            aws.sendMessageToManager(resultMessage);
            System.out.println("Sent success message to manager.");

        } catch (Throwable e) {
            System.err.println("Error processing message: " + e.getClass().getName() + " - " + e.getMessage());
            e.printStackTrace();
            
            // Format: "<TYPE> <URL> ERROR:<description> <APP_ID>"
            String errorDescription = sanitizeErrorMessage(e.getClass().getSimpleName() + "_" + e.getMessage());
            String errorMessage = analysisTypeStr + " " + fileUrl + " ERROR:" + errorDescription + " " + appId;
            aws.sendMessageToManager(errorMessage);
            System.out.println("Sent error message to manager.");
        } finally {
            // Clean up local files
            try {
                Files.deleteIfExists(Paths.get(localInputPath));
                Files.deleteIfExists(Paths.get(localOutputPath));
            } catch (Exception e) {
                System.err.println("Warning: Failed to clean up temp files: " + e.getMessage());
            }
        }

        // Delete the message from the queue
        aws.deleteMessageFromManager(message);
        System.out.println("Deleted message from queue.");
    }

    private static void downloadFile(String fileUrl, String localPath) throws Exception {
        URL url = new URL(fileUrl);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(30000); // 30 seconds
        connection.setReadTimeout(60000); // 60 seconds

        int responseCode = connection.getResponseCode();
        if (responseCode != HttpURLConnection.HTTP_OK) {
            throw new Exception("HTTP error code: " + responseCode);
        }

        try (InputStream in = connection.getInputStream()) {
            Files.copy(in, Paths.get(localPath), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // Errors have spaces which interfere with message parsing - sanitize them
    private static String sanitizeErrorMessage(String message) {
        String sanitized = message.replace(" ", "_")
                                  .replace("\n", "_")
                                  .replace("\r", "_");
        if (sanitized.length() > 100) {
            sanitized = sanitized.substring(0, 100);
        }
        return sanitized;
    }
}
