import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.Tag;
import software.amazon.awssdk.services.ec2.model.*;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;

import java.io.File;
import java.nio.file.Paths;
import java.util.Base64;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

public class AWS {
    private final S3Client s3;
    private final SqsClient sqs;
    private final Ec2Client ec2;
    private static final String MANAGER_AMI_ID = "ami-062055da0d1530fdf";
    private static final String MANAGER_TAG = "Manager";
    private static final String MANAGER_TO_APP_TAG = "ManagerToAppQueue";
    private static final String APP_TO_MANAGER_TAG = "AppToManagerQueue";
    private static final String S3_BUCKET_TAG = "s3bucket";

    private String MANAGER_TO_APP_QUEUE_URL;
    private String APP_TO_MANAGER_QUEUE_URL;
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

    private void createBucketIfNotExists(String bucketName) {
        try {
            s3.createBucket(CreateBucketRequest
                    .builder()
                    .bucket(bucketName)
                    .build());
            s3.waiter().waitUntilBucketExists(HeadBucketRequest.builder()
                    .bucket(bucketName)
                    .build());

            // Enable ACLs by setting Object Ownership to Bucket Owner Preferred
            s3.putBucketOwnershipControls(PutBucketOwnershipControlsRequest.builder()
                    .bucket(bucketName)
                    .ownershipControls(OwnershipControls.builder()
                            .rules(OwnershipControlsRule.builder()
                                    .objectOwnership(ObjectOwnership.BUCKET_OWNER_PREFERRED)
                                    .build())
                            .build())
                    .build());

            // Disable Block Public Access for ACLs to allow public-read ACL on objects
            s3.putPublicAccessBlock(PutPublicAccessBlockRequest.builder()
                    .bucket(bucketName)
                    .publicAccessBlockConfiguration(PublicAccessBlockConfiguration.builder()
                            .blockPublicAcls(false)
                            .ignorePublicAcls(false)
                            .blockPublicPolicy(true)
                            .restrictPublicBuckets(true)
                            .build())
                    .build());

        } catch (S3Exception e) {
            System.out.println(e.getMessage());
        }
    }

    public void uploadToS3(String key, String filePath) {
        s3.putObject(PutObjectRequest.builder().bucket(S3_BUCKET_NAME).key(key).build(),
                RequestBody.fromFile(new File(filePath)));
        System.out.println("Uploaded " + filePath + " to S3 bucket " + S3_BUCKET_NAME + " with key " + key);
    }

    public void downloadFromS3(String key, String filePath) {
        s3.getObject(GetObjectRequest.builder().bucket(S3_BUCKET_NAME).key(key).build(),
                ResponseTransformer.toFile(Paths.get(filePath)));
        System.out.println("Downloaded " + key + " from " + S3_BUCKET_NAME + " to " + filePath);
    }

    public String createSqsQueue(String queueName) {
        CreateQueueRequest createQueueRequest = CreateQueueRequest.builder()
                .queueName(queueName)
                .build();
        CreateQueueResponse queueRes = sqs.createQueue(createQueueRequest);
        return queueRes.queueUrl();
    }

    // only works with from App to Manager
    public void sendMessage(String messageBody) {
        sqs.sendMessage(SendMessageRequest.builder()
                .queueUrl(APP_TO_MANAGER_QUEUE_URL)
                .messageBody(messageBody)
                .build());
    }

    public void findOrCreateManager(int n) {
        DescribeInstancesRequest request = DescribeInstancesRequest.builder()
                .filters(Filter.builder().name("instance-state-name").values("running", "pending").build(),
                        Filter.builder().name("tag:Name").values(MANAGER_TAG).build())
                .build();

        DescribeInstancesResponse describeInstancesResponse = ec2.describeInstances(request);

        boolean managerFound = false;
        for (Reservation reservation : describeInstancesResponse.reservations()) {
            if (!reservation.instances().isEmpty()) {
                managerFound = true;
                break;
            }
        }

        if (managerFound) {
            // Get existing queue URLs from tags
            System.out.println("Manager is already running.");
            for (Reservation reservation : describeInstancesResponse.reservations()) {
                for (Instance remoteInstance : reservation.instances()) {
                    for (Tag tag : remoteInstance.tags()) {
                        if (tag.key().equals(APP_TO_MANAGER_TAG)) {
                            APP_TO_MANAGER_QUEUE_URL = tag.value();
                        } 
                        else if (tag.key().equals(MANAGER_TO_APP_TAG)) {
                            MANAGER_TO_APP_QUEUE_URL = tag.value();
                        }
                        else if (tag.key().equals(S3_BUCKET_TAG)) {
                            S3_BUCKET_NAME = tag.value();
                        }
                    }
                }
            }
        }
        else {
            System.out.println("Manager not found. Starting a new Manager instance...");

            // create SQS queues
            MANAGER_TO_APP_QUEUE_URL = createSqsQueue(MANAGER_TO_APP_TAG);
            APP_TO_MANAGER_QUEUE_URL = createSqsQueue(APP_TO_MANAGER_TAG);

            // create s3 bucket
            S3_BUCKET_NAME = S3_BUCKET_TAG + "-" + DateTimeFormatter.ofPattern("HHmmss").withZone(ZoneId.of("Asia/Jerusalem")).format(Instant.now());
            createBucketIfNotExists(S3_BUCKET_NAME);

            // Upload the manager JAR to S3
            uploadToS3("Manager-1.0-SNAPSHOT.jar", "../Manager/target/Manager-1.0-SNAPSHOT.jar");
            
            // Upload the worker JAR to S3
            uploadToS3("Worker-1.0-SNAPSHOT.jar", "../Worker/target/Worker-1.0-SNAPSHOT.jar");

            String script = 
                        "#!/bin/bash\n" +
                        "yum update -y\n" +
                        "yum install java-17-amazon-corretto -y\n" +
                        "cd /home/ec2-user\n" +
                        "echo \"Downloading manager.jar\"\n" +
                        "aws s3 cp s3://" + S3_BUCKET_NAME + "/Manager-1.0-SNAPSHOT.jar .\n" +
                        "java -jar Manager-1.0-SNAPSHOT.jar " + n + " > /dev/console 2>&1"; 

            RunInstancesRequest runRequest = RunInstancesRequest.builder()
                .imageId(MANAGER_AMI_ID)
                .instanceType(InstanceType.T3_LARGE)
                .minCount(1)
                .maxCount(1)
                .keyName("vockey")
                .userData(Base64.getEncoder().encodeToString(script.getBytes()))
                .tagSpecifications(
                    TagSpecification.builder()
                    .resourceType(ResourceType.INSTANCE)
                    .tags(
                        Tag.builder().key("Name").value(MANAGER_TAG).build(),
                        Tag.builder().key(APP_TO_MANAGER_TAG).value(APP_TO_MANAGER_QUEUE_URL).build(),
                        Tag.builder().key(MANAGER_TO_APP_TAG).value(MANAGER_TO_APP_QUEUE_URL).build(),
                        Tag.builder().key(S3_BUCKET_TAG).value(S3_BUCKET_NAME).build()
                    )
                    .build())
                .iamInstanceProfile(
                    IamInstanceProfileSpecification.builder()
                    .name("LabInstanceProfile")
                    .build())
                .build();

            ec2.runInstances(runRequest);
            System.out.println("Manager instance started.");
        }
    }

    public Message receiveMessage(String appId) {
        // Create the receive message request
        ReceiveMessageRequest receiveRequest = ReceiveMessageRequest.builder()
                .queueUrl(MANAGER_TO_APP_QUEUE_URL)
                .maxNumberOfMessages(5) 
                .waitTimeSeconds(20) 
                .build();

        // Continuously poll the queue until we find our message
        Message myMessage = null;
        while(myMessage == null) {
            List<Message> messages = sqs.receiveMessage(receiveRequest).messages();
            for (Message message : messages) {
                String[] bodyParts = message.body().split(":");

                // Check if the message has our correlation ID - if not release it back to the queue
                if (bodyParts.length > 2 && bodyParts[bodyParts.length - 1].equals(appId)) {
                    sqs.changeMessageVisibility(ChangeMessageVisibilityRequest.builder()
                            .queueUrl(MANAGER_TO_APP_QUEUE_URL)
                            .receiptHandle(message.receiptHandle())
                            .visibilityTimeout(100)
                            .build());
                    myMessage = message;
                }
                else {
                    sqs.changeMessageVisibility(ChangeMessageVisibilityRequest.builder()
                            .queueUrl(MANAGER_TO_APP_QUEUE_URL)
                            .receiptHandle(message.receiptHandle())
                            .visibilityTimeout(0)
                            .build());
                }
            }
        }
        return myMessage;
    }

    public void deleteMessage(Message message) {
        DeleteMessageRequest deleteRequest = DeleteMessageRequest.builder()
                .queueUrl(MANAGER_TO_APP_QUEUE_URL)
                .receiptHandle(message.receiptHandle())
                .build();
        sqs.deleteMessage(deleteRequest);
    }

    public void cleanup() {
        s3.close();
        sqs.close();
        ec2.close();
    }
}
