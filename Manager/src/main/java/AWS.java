import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesRequest;
import software.amazon.awssdk.services.ec2.model.CreateTagsRequest;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesResponse;
import software.amazon.awssdk.services.ec2.model.Instance;
import software.amazon.awssdk.services.ec2.model.Reservation;
import software.amazon.awssdk.services.ec2.model.Tag;
import software.amazon.awssdk.services.ec2.model.TerminateInstancesRequest;
import software.amazon.awssdk.services.ec2.model.RunInstancesRequest;
import software.amazon.awssdk.services.ec2.model.InstanceType;
import software.amazon.awssdk.services.ec2.model.TagSpecification;
import software.amazon.awssdk.services.ec2.model.ResourceType;
import software.amazon.awssdk.services.ec2.model.IamInstanceProfileSpecification;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.DeleteQueueRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.ec2.model.Filter;

import java.io.File;
import java.nio.file.Paths;
import java.util.List;
import java.util.Base64;

public class AWS {
    private final S3Client s3;
    private final SqsClient sqs;
    private final Ec2Client ec2;

    private static final String MANAGER_TO_APP_TAG = "ManagerToAppQueue";
    private static final String APP_TO_MANAGER_TAG = "AppToManagerQueue";
    private static final String WORKER_TO_MANAGER_TAG = "WorkerToManagerQueue";
    private static final String MANAGER_TO_WORKER_TAG = "ManagerToWorkerQueue";
    private static final String MANAGER_TAG = "Manager";
    private static final String WORKER_TAG = "Worker";
    private static final String S3_BUCKET_TAG = "s3bucket";
    private static final String WORKER_AMI_ID = "ami-062055da0d1530fdf";

    private String MANAGER_TO_APP_QUEUE_URL;
    private String APP_TO_MANAGER_QUEUE_URL;
    private String WORKER_TO_MANAGER_QUEUE_URL;
    private String MANAGER_TO_WORKER_QUEUE_URL;
    private String S3_BUCKET_NAME;
    private String INSTANCE_ID;

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
                        Filter.builder().name("tag:Name").values(MANAGER_TAG).build())
                .build();
        DescribeInstancesResponse response = ec2.describeInstances(request);

        while (response.reservations().size() == 0 || response.reservations().get(0).instances().size() == 0) {
             // No running Manager instance found, wait and retry
            try {
                System.out.println("Waiting for Manager instance to start...");
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            response = ec2.describeInstances(request);
        }
        
        for (Reservation reservation : response.reservations()) {
            for (Instance ec2instance : reservation.instances()) {
                INSTANCE_ID = ec2instance.instanceId();
                for (Tag tag : ec2instance.tags()) {
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

    private List<Message> receiveMessages(String queueUrl) {
        ReceiveMessageRequest receiveRequest = ReceiveMessageRequest.builder()
                .queueUrl(queueUrl)
                .maxNumberOfMessages(1)
                .waitTimeSeconds(20)
                .build();
        return sqs.receiveMessage(receiveRequest).messages();
    }

    private void deleteMessage(Message message, String queueUrl) {
        DeleteMessageRequest deleteRequest = DeleteMessageRequest.builder()
                .queueUrl(queueUrl)
                .receiptHandle(message.receiptHandle())
                .build();
        sqs.deleteMessage(deleteRequest);
    }

    private void sendMessage(String messageBody, String queueUrl) {
        sqs.sendMessage(SendMessageRequest.builder()
                .queueUrl(queueUrl)
                .messageBody(messageBody)
                .build());
    }

    public List<Message> receiveMessagesFromLocalApplication() {
        return receiveMessages(APP_TO_MANAGER_QUEUE_URL);
    }

    public List<Message> receiveMessagesFromWorkers() {
        return receiveMessages(WORKER_TO_MANAGER_QUEUE_URL);
    }

    public void deleteMessageFromLocalApplication(Message message) {
        deleteMessage(message, APP_TO_MANAGER_QUEUE_URL);
    }

    public void deleteMessageFromWorkers(Message message) {
        deleteMessage(message, WORKER_TO_MANAGER_QUEUE_URL);
    }

    public void sendMessageToLocalApplication(String messageBody) {
        sendMessage(messageBody, MANAGER_TO_APP_QUEUE_URL);
    }

    public void sendMessageToWorkers(String messageBody) {
        sendMessage(messageBody, MANAGER_TO_WORKER_QUEUE_URL);
    }

    public void uploadToS3(String key, String filePath) {
        s3.putObject(PutObjectRequest.builder().bucket(S3_BUCKET_NAME).key(key).build(),
                RequestBody.fromFile(new File(filePath)));
    }

    public void downloadFromS3(String key, String filePath) {
        s3.getObject(GetObjectRequest.builder().bucket(S3_BUCKET_NAME).key(key).build(),
                ResponseTransformer.toFile(Paths.get(filePath)));
    }

    public void terminateInstance() {
        TerminateInstancesRequest terminateRequest = TerminateInstancesRequest.builder()
                .instanceIds(INSTANCE_ID)
                .build();
        ec2.terminateInstances(terminateRequest);
    }

    // Delete all objects in the bucket except the "processed/" folder
    public void deleteBucket() {
        ListObjectsV2Request listObjectsV2Request = ListObjectsV2Request.builder().bucket(S3_BUCKET_NAME).build();
        ListObjectsV2Response listObjectsV2Response;
        do {
            listObjectsV2Response = s3.listObjectsV2(listObjectsV2Request);
            for (S3Object s3Object : listObjectsV2Response.contents()) {
                if (!s3Object.key().startsWith("processed/")) {
                    s3.deleteObject(DeleteObjectRequest.builder().bucket(S3_BUCKET_NAME).key(s3Object.key()).build());
                }
            }
            listObjectsV2Request = listObjectsV2Request.toBuilder().continuationToken(listObjectsV2Response.nextContinuationToken()).build();
        } while (listObjectsV2Response.isTruncated());
    }

    public void deleteQueues() {
        DeleteQueueRequest deleteQueueRequest1 = DeleteQueueRequest.builder().queueUrl(APP_TO_MANAGER_QUEUE_URL).build();
        DeleteQueueRequest deleteQueueRequest2 = DeleteQueueRequest.builder().queueUrl(MANAGER_TO_APP_QUEUE_URL).build();
        DeleteQueueRequest deleteQueueRequest3 = DeleteQueueRequest.builder().queueUrl(WORKER_TO_MANAGER_QUEUE_URL).build();
        DeleteQueueRequest deleteQueueRequest4 = DeleteQueueRequest.builder().queueUrl(MANAGER_TO_WORKER_QUEUE_URL).build();
        sqs.deleteQueue(deleteQueueRequest1);
        sqs.deleteQueue(deleteQueueRequest2);
        sqs.deleteQueue(deleteQueueRequest3);
        sqs.deleteQueue(deleteQueueRequest4);
    }

    public String createQueue(String queueName) {
        CreateQueueRequest createQueueRequest = CreateQueueRequest.builder()
                .queueName(queueName)
                .build();
        return sqs.createQueue(createQueueRequest).queueUrl();
    }

    public void createWorkerQueues() {
        WORKER_TO_MANAGER_QUEUE_URL = createQueue(WORKER_TO_MANAGER_TAG);
        MANAGER_TO_WORKER_QUEUE_URL = createQueue(MANAGER_TO_WORKER_TAG);
        
        Tag workerToManagerTag = Tag.builder().key(WORKER_TO_MANAGER_TAG).value(WORKER_TO_MANAGER_QUEUE_URL).build();
        Tag managerToWorkerTag = Tag.builder().key(MANAGER_TO_WORKER_TAG).value(MANAGER_TO_WORKER_QUEUE_URL).build();
        
        CreateTagsRequest createTagsRequest = CreateTagsRequest.builder()
                .resources(INSTANCE_ID)
                .tags(workerToManagerTag, managerToWorkerTag)
                .build();
        ec2.createTags(createTagsRequest);
    }

    public List<Message> receiveMessagesFromWorkersToManager() {
        return receiveMessages(WORKER_TO_MANAGER_QUEUE_URL);
    }

    public void deleteMessageFromWorkersToManager(Message message) {
        deleteMessage(message, WORKER_TO_MANAGER_QUEUE_URL);
    }

    public int countWorkers() {
        DescribeInstancesRequest request = DescribeInstancesRequest.builder()
                .filters(Filter.builder().name("instance-state-name").values("running", "pending").build(),
                        Filter.builder().name("tag:Name").values(WORKER_TAG).build())
                .build();
        DescribeInstancesResponse response = ec2.describeInstances(request);
        int count = 0;
        for (Reservation reservation : response.reservations()) {
            count += reservation.instances().size();
        }
        return count;
    }

    public void createWorkers(int count) {
        if (count <= 0) return;

        String script = "#!/bin/bash\n" +
                        "yum update -y\n" +
                        "yum install java-17-amazon-corretto -y\n" +
                        "cd /home/ec2-user\n" +
                        "echo \"Downloading Worker JAR\"\n" +
                        "aws s3 cp s3://" + S3_BUCKET_NAME + "/Worker-1.0-SNAPSHOT.jar .\n" +
                        "java -jar Worker-1.0-SNAPSHOT.jar > /dev/console 2>&1";

        RunInstancesRequest runRequest = RunInstancesRequest.builder()
                .imageId(WORKER_AMI_ID)
                .instanceType(InstanceType.T3_LARGE)
                .minCount(count)
                .maxCount(count)
                .keyName("vockey")
                .userData(Base64.getEncoder().encodeToString(script.getBytes()))
                .tagSpecifications(
                        TagSpecification.builder()
                                .resourceType(ResourceType.INSTANCE)
                                .tags(
                                        Tag.builder().key("Name").value(WORKER_TAG).build(),
                                        Tag.builder().key(WORKER_TO_MANAGER_TAG).value(WORKER_TO_MANAGER_QUEUE_URL).build(),
                                        Tag.builder().key(MANAGER_TO_WORKER_TAG).value(MANAGER_TO_WORKER_QUEUE_URL).build(),
                                        Tag.builder().key(S3_BUCKET_TAG).value(S3_BUCKET_NAME).build()
                                )
                                .build())
                .iamInstanceProfile(
                        IamInstanceProfileSpecification.builder()
                                .name("LabInstanceProfile")
                                .build())
                .build();

        ec2.runInstances(runRequest);
        System.out.println("Started " + count + " worker instances.");
    }

    public void terminateAllWorkers() {
        DescribeInstancesRequest request = DescribeInstancesRequest.builder()
                .filters(Filter.builder().name("instance-state-name").values("running", "pending").build(),
                        Filter.builder().name("tag:Name").values(WORKER_TAG).build())
                .build();
        DescribeInstancesResponse response = ec2.describeInstances(request);

        for (Reservation reservation : response.reservations()) {
            for (Instance instance : reservation.instances()) {
                TerminateInstancesRequest terminateRequest = TerminateInstancesRequest.builder()
                        .instanceIds(instance.instanceId())
                        .build();
                ec2.terminateInstances(terminateRequest);
                System.out.println("Terminated worker instance: " + instance.instanceId());
            }
        }
    }
}
