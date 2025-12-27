import java.io.File;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesRequest;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesResponse;
import software.amazon.awssdk.services.ec2.model.Filter;
import software.amazon.awssdk.services.ec2.model.Instance;
import software.amazon.awssdk.services.ec2.model.Reservation;
import software.amazon.awssdk.services.ec2.model.Tag;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import java.util.List;

import software.amazon.awssdk.services.s3.model.ObjectCannedACL;

public class AWS {
    private final S3Client s3;
    private final SqsClient sqs;
    private final Ec2Client ec2;

    private static final String WORKER_TO_MANAGER_TAG = "WorkerToManagerQueue";
    private static final String MANAGER_TO_WORKER_TAG = "ManagerToWorkerQueue";
    private static final String WORKER_TAG = "Worker";
    private static final String S3_BUCKET_TAG = "s3bucket";

    private String WORKER_TO_MANAGER_QUEUE_URL;
    private String MANAGER_TO_WORKER_QUEUE_URL;
    private String S3_BUCKET_NAME;

    public static Region region = Region.US_EAST_1;

    private static final AWS instance = new AWS();

    private AWS() {
        s3 = S3Client.builder().region(region).build();
        sqs = SqsClient.builder().region(region).build();
        ec2 = Ec2Client.builder().region(region).build();
    }

    public static AWS getInstance() {
        return instance;
    }

    public void getEC2Tags() {
        DescribeInstancesRequest request = DescribeInstancesRequest.builder()
                .filters(Filter.builder().name("instance-state-name").values("running", "pending").build(),
                        Filter.builder().name("tag:Name").values(WORKER_TAG).build())
                .build();
        DescribeInstancesResponse response = ec2.describeInstances(request);

        while (response.reservations().isEmpty() || response.reservations().get(0).instances().isEmpty()) {
            try {
                System.out.println("Waiting for Worker instance to start...");
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            response = ec2.describeInstances(request);
        }

        for (Reservation reservation : response.reservations()) {
            for (Instance ec2instance : reservation.instances()) {
                for (Tag tag : ec2instance.tags()) {
                    if (tag.key().equals(WORKER_TO_MANAGER_TAG)) {
                        WORKER_TO_MANAGER_QUEUE_URL = tag.value();
                    } else if (tag.key().equals(MANAGER_TO_WORKER_TAG)) {
                        MANAGER_TO_WORKER_QUEUE_URL = tag.value();
                    } else if (tag.key().equals(S3_BUCKET_TAG)) {
                        S3_BUCKET_NAME = tag.value();
                    }
                }
                // Only need tags from one instance
                if (WORKER_TO_MANAGER_QUEUE_URL != null && MANAGER_TO_WORKER_QUEUE_URL != null && S3_BUCKET_NAME != null) {
                    return;
                }
            }
        }
    }

    public List<Message> receiveMessagesFromManager() {
        ReceiveMessageRequest receiveRequest = ReceiveMessageRequest.builder()
                .queueUrl(MANAGER_TO_WORKER_QUEUE_URL)
                .maxNumberOfMessages(1)
                .waitTimeSeconds(20)
                .visibilityTimeout(2700)
                .build();
        return sqs.receiveMessage(receiveRequest).messages();
    }

    public void deleteMessageFromManager(Message message) {
        DeleteMessageRequest deleteRequest = DeleteMessageRequest.builder()
                .queueUrl(MANAGER_TO_WORKER_QUEUE_URL)
                .receiptHandle(message.receiptHandle())
                .build();
        sqs.deleteMessage(deleteRequest);
    }

    public void sendMessageToManager(String messageBody) {
        sqs.sendMessage(SendMessageRequest.builder()
                .queueUrl(WORKER_TO_MANAGER_QUEUE_URL)
                .messageBody(messageBody)
                .build());
    }

    public String uploadToS3(String key, String filePath) throws UnsupportedEncodingException {
        s3.putObject(PutObjectRequest.builder().bucket(S3_BUCKET_NAME).acl(ObjectCannedACL.PUBLIC_READ).key(key).build(),
                RequestBody.fromFile(new File(filePath)));
        return "https://" + S3_BUCKET_NAME + ".s3.amazonaws.com/" + URLEncoder.encode(key, StandardCharsets.UTF_8.toString());
    }

    public String uploadStringToS3(String key, String content) throws UnsupportedEncodingException {
        s3.putObject(
            PutObjectRequest.builder()
                .bucket(S3_BUCKET_NAME)
                .key(key)
                .build(),
            RequestBody.fromString(content)
        );
        return "https://" + S3_BUCKET_NAME + ".s3.amazonaws.com/" + URLEncoder.encode(key, StandardCharsets.UTF_8.toString());
    }

    public void downloadFromS3(String key, String localPath) throws Exception {
        GetObjectRequest getRequest = GetObjectRequest.builder()
            .bucket(S3_BUCKET_NAME)
            .key(key)
            .build();
        
        try (InputStream inputStream = s3.getObject(getRequest)) {
            Files.copy(inputStream, Paths.get(localPath), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public String downloadStringFromS3(String key) throws Exception {
        GetObjectRequest getRequest = GetObjectRequest.builder()
            .bucket(S3_BUCKET_NAME)
            .key(key)
            .build();
        
        try (InputStream inputStream = s3.getObject(getRequest)) {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    public void deleteFromS3(String key) {
        DeleteObjectRequest deleteRequest = DeleteObjectRequest.builder()
            .bucket(S3_BUCKET_NAME)
            .key(key)
            .build();
        s3.deleteObject(deleteRequest);
    }
}
