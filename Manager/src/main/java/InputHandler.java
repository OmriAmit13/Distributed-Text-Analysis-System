import software.amazon.awssdk.services.sqs.model.Message;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

class InputHandler implements Runnable { 

    private static final AWS aws = AWS.getInstance();
    private static final int MAX_WORKERS = 8;

    @Override
    public void run() {
        while (!Manager.isTerminated()) {
            List<Message> messages = aws.receiveMessagesFromLocalApplication();
            if (!messages.isEmpty()) {
                Message message = messages.get(0);
                String body = message.body();
                if (body.startsWith("new task:")) {
                    handleNewTask(message);
                } else if (body.equals("terminate")) {
                    Manager.terminate();
                    handleTermination();
                }
                aws.deleteMessageFromLocalApplication(message);
            }
        }
    }

    private static void handleNewTask(Message message) {
        String[] parts = message.body().split(":");
        String s3Key = parts[1];
        String appId = parts[2];
        String inputFilePath = "inputs/" + appId + "/input.txt";
        String outputFilePath = "outputs/" + appId + "/output.html";

        new File(inputFilePath).getParentFile().mkdirs();
        new File(outputFilePath).getParentFile().mkdirs();

        aws.downloadFromS3(s3Key, inputFilePath);

        List<String> malformedLines = validateFile(inputFilePath);
        if (!malformedLines.isEmpty()) {
            // Create and upload malformed lines HTML
            System.out.println("Malformed lines found in input file for appId " + appId);
            createMalformedLinesHtml(outputFilePath, malformedLines);
            aws.uploadToS3("outputs/" + appId + "/output.html", outputFilePath);
            aws.sendMessageToLocalApplication("done:outputs/" + appId + "/output.html:" + appId);
        }
        else {
            // Process the valid input file and generate output HTML
            System.out.println("Processing valid input file for appId " + appId);
            List<String> messagesForWorkers = createSQSMessagesForWorkers(inputFilePath, appId);
            Manager.addNewTask(appId, messagesForWorkers.size());
            for (String workerMessage : messagesForWorkers) {
                aws.sendMessageToWorkers(workerMessage);
            }
            createWorkers(messagesForWorkers.size());
        }
    }

    private static void createWorkers(int messagesCount) {
        int n = Manager.getN();
        int workersNeeded = (messagesCount + n - 1) / n;
        int currentWorkers = aws.countWorkers();
        int workersToCreate = Math.max(0, workersNeeded - currentWorkers);
        if (currentWorkers + workersToCreate > MAX_WORKERS) {
            workersToCreate = MAX_WORKERS - currentWorkers;
        }
        aws.createWorkers(workersToCreate);
    }

    private static void handleTermination() {
        // Create and upload termination HTML
        String terminationHtmlPath = "termination.html";
        createTerminationHtml(terminationHtmlPath);
        String terminationS3Key = "outputs/termination.html";
        aws.uploadToS3(terminationS3Key, terminationHtmlPath);

        // Process remaining messages
        List<Message> remainingMessages;
        do {
            remainingMessages = aws.receiveMessagesFromLocalApplication();
            for (Message message : remainingMessages) {
                if (message.body().startsWith("new task:")) {
                    String appId = message.body().split(":")[2];
                    aws.sendMessageToLocalApplication("done:" + terminationS3Key + ":" + appId);
                }
                aws.deleteMessageFromLocalApplication(message);
            }
        } while (!remainingMessages.isEmpty());

        System.out.println("InputHandler terminating.");
        Manager.finishInputHandler();
    }

    private static List<String> validateFile(String filePath) {
        List<String> malformedLines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) {
                    continue;
                }
                String[] parts = line.split("\t");
                if (parts.length != 2) {
                    malformedLines.add(line);
                    continue;
                }
                String type = parts[0];
                String url = parts[1];
                if (!type.equals("POS") && !type.equals("CONSTITUENCY") && !type.equals("DEPENDENCY")) {
                    malformedLines.add(line);
                } else {
                    try {
                        new URL(url).toURI();
                    } catch (Exception e) {
                        malformedLines.add(line);
                    }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return malformedLines;
    }

    private static void createMalformedLinesHtml(String filePath, List<String> malformedLines) {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(filePath))) {
            writer.write("<html><body>");
            writer.write("<h1>The input file is invalid.</h1>");
            writer.write("<h2>Malformed Lines:</h2>");
            writer.write("<ul>");
            for (String line : malformedLines) {
                writer.write("<li>" + line + "</li>");
            }
            writer.write("</ul>");
            writer.write("</body></html>");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void createTerminationHtml(String filePath) {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(filePath))) {
            writer.write("<html><body><h1>Service Terminated</h1><p>The service is no longer accepting tasks.</p></body></html>");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static List<String> createSQSMessagesForWorkers(String inputFilePath, String appId) {
        List<String> messages = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(inputFilePath))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) {
                    continue;
                }
                String[] parts = line.split("\t");
                String type = parts[0];
                String url = parts[1];
                String message = type + " " + url + " " + appId;
                messages.add(message);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return messages;
    }
}
