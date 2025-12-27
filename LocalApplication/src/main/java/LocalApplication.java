import java.io.IOException;

import software.amazon.awssdk.services.sqs.model.Message;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;
import java.nio.file.Files;

public class LocalApplication {

    final static AWS aws = AWS.getInstance();

    public static void main(String[] args) {
        if (args.length < 3) {
            System.out.println("Usage: java -jar yourjar.jar <inputFileName> <outputFileName> <n> [terminate]");
            return;
        }

        String appId = UUID.randomUUID().toString();
        String inputFileName = args[0];
        String outputFileName = args[1];
        int n = Integer.parseInt(args[2]);
        boolean terminate = args.length > 3 && "terminate".equalsIgnoreCase(args[3]);

        try {
            // validate inputs
            if (!validateInputs(inputFileName, outputFileName, n)) {
                return;
            }

            // check if a Manager node is active, else - start one
            aws.findOrCreateManager(n);

            // upload the input file to S3
            String s3Key = "inputs/" + appId + "/" + Paths.get(inputFileName).getFileName().toString();
            aws.uploadToS3(s3Key, "data/" + inputFileName);

            // send a message to an SQS queue saying where the location of the file in S3
            String messageBody = "new task:" + s3Key + ":" + appId;
            aws.sendMessage(messageBody);
            System.out.println("Sent new task message to Manager.");

            // check the SQS for message indicating that process is done
            System.out.println("Waiting for completion message from Manager...");
            Message doneMessage = aws.receiveMessage(appId);
            String doneS3Key = doneMessage.body().split(":")[1];
            System.out.println("Received completion message.");

            // get the summary output file from S3
            aws.downloadFromS3(doneS3Key, "data/" + outputFileName);

            // delete the "done" message from the queue
            aws.deleteMessage(doneMessage);

            // if terminate mode - send termination message to the Manager
            if (terminate) {
                aws.sendMessage("terminate");
                System.out.println("Sent terminate message to Manager.");
            }
        } 
        catch (Exception e) {
            // handle local exceptions appropriately : tell manager to cancel the operation
            System.err.println("An error occurred: " + e.getMessage());
            String messageBody = "cancel operation:" + appId;
            aws.sendMessage(messageBody);
            System.out.println("Sent cancel operation message to Manager.");
        } 
        finally {
            aws.cleanup();
        }
    }

    private static boolean validateInputs(String inputFileName, String outputFileName, int n) {
        // checks of the input file
        Path inputFilePath = Paths.get("data", inputFileName);
        if (!Files.exists(inputFilePath) || !Files.isRegularFile(inputFilePath)) {
            System.err.println("Error: Input file not found or is not a regular file at " + inputFilePath);
            return false;
        }
        if (!inputFileName.toLowerCase().endsWith(".txt")) {
            System.err.println("Error: Input file must be a .txt file.");
            return false;
        }

        // checks of the output file
        if (!outputFileName.toLowerCase().endsWith(".html")) {
            System.err.println("Error: Output file must be an .html file.");
            return false;
        }

        // if the output file already exist delete it
        Path outputFilePath = Paths.get("data", outputFileName);
        if (Files.exists(outputFilePath)) {
            try {
                System.out.println("Output file already exists. Deleting existing file at " + outputFilePath);
                Files.delete(outputFilePath);
            } catch (IOException e) {
                System.err.println("Error: Unable to delete existing output file at " + outputFilePath);
                return false;
            }
        }

        // check for n
        if (n <=0) {
            System.err.println("Error: n must be a positive integer.");
            return false;
        }
        System.out.println("Input validation passed.");
        return true;
    }
}
