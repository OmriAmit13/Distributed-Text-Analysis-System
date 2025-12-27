import software.amazon.awssdk.services.sqs.model.Message;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

class OutputHandler implements Runnable { 

    private static final AWS aws = AWS.getInstance();
    private static final ConcurrentHashMap<String, List<String>> processedParts = new ConcurrentHashMap<>();

    @Override
    public void run() {
        while (!Manager.isTerminated()) {
            getAndHandleMessage();
        }
        handleTermination();
    }

    private void getAndHandleMessage() {
        List<Message> messages = aws.receiveMessagesFromWorkersToManager();
        if (!messages.isEmpty()) {
            Message message = messages.get(0);
            String body = message.body();
            String[] parts = body.split(" ");
            String task = parts[0];
            String url = parts[1];
            String resultS3Key = parts[2];
            String appId = parts[3];
            
            String lineForHtmlOutput = "<li>" + task + ": " + url + " " + resultS3Key + "</li>";
            
            processedParts.putIfAbsent(appId, new ArrayList<>());
            processedParts.get(appId).add(lineForHtmlOutput);

            if (Manager.fileProcessed(appId)) {
                finishTask(appId);
            }

            aws.deleteMessageFromWorkersToManager(message);
        }
    }

    private void finishTask(String appId) {
        List<String> lines = processedParts.remove(appId);
        String outputFilePath = "outputs/" + appId + "/output.html";
        File file = new File(outputFilePath);
        
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(file))) {
            writer.write("<html><body><h1>Analysis Results</h1><ul>");
            for (String line : lines) {
                writer.write(line);
                writer.newLine();
            }
            writer.write("</ul></body></html>");
        } catch (IOException e) {
            e.printStackTrace();
        }

        aws.uploadToS3(outputFilePath, outputFilePath);
        file.delete();

        aws.sendMessageToLocalApplication("done:" + outputFilePath + ":" + appId);
    }

    private void handleTermination() {
        System.out.println("OutputHandler handling termination.");
        // wait for all tasks to finish
        while (!processedParts.isEmpty() || !Manager.inputHandlerFinished() || Manager.hasPendingTasks()) {
            getAndHandleMessage();
        }

        System.out.println("OutputHandler terminating.");

        // Delete resources
        try {
            Thread.sleep(5000); // Wait for messages to be processed
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        aws.deleteQueues();
        aws.deleteBucket();
        aws.terminateAllWorkers();
        aws.terminateInstance();
    }
}
