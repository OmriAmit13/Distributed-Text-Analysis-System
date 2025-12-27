# Distributed Text Analysis System

## Authors
- **Omri Amit** - 207601691
- **Yarin Binyamin** - 318492840

---

## Overview

The system follows a distributed architecture with three main components:

```
┌─────────────────┐     ┌─────────────────┐     ┌─────────────────┐
│ LocalApplication│────▶│     Manager    │────▶│     Workers     │
│    (Client)     │◀────│                │◀────│                 │
└─────────────────┘     └─────────────────┘     └─────────────────┘
         │                      │                       │
         │                      │                       │
         ▼                      ▼                       ▼
    ┌─────────────────────────────────────────────────────────┐
    │                    AWS Services                          │
    │  ┌─────┐    ┌─────────────────────┐    ┌──────────────┐ │
    │  │ S3  │    │   SQS (4 Queues)    │    │     EC2      │ │
    │  └─────┘    └─────────────────────┘    └──────────────┘ │
    └─────────────────────────────────────────────────────────┘
```

### Component Details

#### 1. LocalApplication (Client)
*Location*: `LocalApplication/src/main/java/`

- Validates input file and parameters
- Checks if Manager exists, or creates one
- Creates S3 bucket and SQS queues if Manager doesn't exist
- Uploads input file to S3
- Sends task message to Manager via SQS
- Waits for completion message
- Downloads result HTML from S3
- Sends termination signal if user requested

#### 2. Manager
*Location*: `Manager/src/main/java/`

- Runs two parallel threads: InputHandler and OutputHandler
- Manages worker lifecycle
- Handles termination

**InputHandler Thread:**
- Receives new task messages from LocalApplication
- Downloads input files from S3
- Validates input file format
- Parses input and creates individual work messages for workers
- Calculates number of workers needed based on `n` ratio
- Creates worker EC2 instances (max 8 workers as AWS didn't allow more than that)

**OutputHandler Thread:**
- Receives completed task messages from Workers
- Merges results per appId
- Generates HTML output when all tasks are done
- Uploads result to S3 and sends done message to LocalApplication
- Handles deletion of AWS services on termination

#### 3. Worker (Processor)
*Location*: `Worker/src/main/java/`

- Polls for work messages from Manager
- Downloads text files from URLs
- Performs NLP analysis using Stanford CoreNLP
- Uploads results to S3
- Sends completion message to Manager

---

## How to Run the Project

### Prerequisites
1. **Java 17** installed
2. **Maven** installed
3. **AWS Credentials** configured (via AWS CLI or environment variables)
4. **AWS Academy Lab** running with LabInstanceProfile available

### Build Instructions
From the project root directory, run:
```bash
./buildAll.bat
```
Or navigate to each project individually and run:
```
mvn clean package
```
This will build all three Maven projects (LocalApplication, Manager, Worker) and create the JAR files.

### Run the Application
Navigate to the LocalApplication directory and run:
```bash
cd LocalApplication
java -jar target/text-analysis-app-1.0-SNAPSHOT-jar-with-dependencies.jar <inputFileName> <outputFileName> <n> [terminate]
```

**Parameters:**
- `inputFileName`: Name of the input file (must be in `data/` folder, must be `.txt`)
- `outputFileName`: Name of the output file (will be created in `data/` folder, must be `.html`)
- `n`: Number of messages per worker ratio (workers = messages / n)
- `terminate` (optional): If present, terminates the Manager after processing

**Example:**
```bash
java -jar target/text-analysis-app-1.0-SNAPSHOT-jar-with-dependencies.jar input-sample.txt output.html 2
```

### Clean Solution
From the project root directory, run:
```bash
./cleanAll.bat
```
Or navigate to each project individually and run:
```
mvn clean
```
This will clean all three Maven projects.

### Input File Format
Each line in the input file should contain:
```
<ANALYSIS_TYPE><TAB><URL>
```
Where:
- `ANALYSIS_TYPE` is one of: `POS`, `CONSTITUENCY`, `DEPENDENCY`
- `URL` is a valid URL to a text file

---

## AWS Resources Used

### S3 Bucket
- **Naming**: `s3bucket-<timestamp>`
- **Contents**:
  - `inputs/<appId>/` - Input files from clients
  - `outputs/<appId>/` - Result HTML files
  - `processed/<appId>/` - Individual analysis results (public-read)

### SQS Queues
1. **AppToManagerQueue**: LocalApplication → Manager
2. **ManagerToAppQueue**: Manager → LocalApplication
3. **ManagerToWorkerQueue**: Manager → Workers
4. **WorkerToManagerQueue**: Workers → Manager

### EC2 Instances
- **Manager**: Single instance, T3_LARGE, tagged with queue URLs and bucket name
- **Workers**: Up to 8 instances, T3_LARGE, created dynamically based on workload

---

## Instance Configuration
- **AMI**: `ami-062055da0d1530fdf` (or any other AMI which contains only Linux and vockey key name)
- **Instance Type**: `T3_LARGE`
- **Region**: `US-EAST-1`

---

## Performance Results
- **Execution Time**: ~25 minutes
- **n Value Used**: 2
- **Input**: 9 analysis tasks (3 files × 3 analysis types: POS, CONSTITUENCY, DEPENDENCY)

---

## Security

**How credentials are handled:**
- NO hardcoded credentials in the code
- Uses IAM Instance Profile (`LabInstanceProfile`) for EC2 instances
- All EC2 instances automatically receive temporary credentials via the instance metadata service
- AWS SDK automatically retrieves credentials from the instance profile
- Queue URLs and bucket names are passed via EC2 instance tags (not sensitive data)

**Security measures:**
- S3 bucket has public access blocked except for specific object ACLs in order to allow access to processed output files
- SQS queues are private and only accessible within the AWS account
- No credentials are transmitted over the network or stored in files

---

## Scalability

1. **SQS Queues**: 
   - SQS supports unlimited message
   - Each client gets a unique `appId` UUID, preventing message collisions
   - Messages are filtered by `appId` so clients only receive their own responses

2. **Worker Scaling**:
   - Workers are created based on workload: `workers = messages / n`
   - Maximum of 8 workers to stay within AWS Academy limits

3. **S3 Storage**:
   - S3 supports unlimited objects
   - Each client's files are namespaced by `appId`

4. **Stateless Workers**:
   - Workers don't maintain state between tasks
   - Any worker can process any message
   - Failed workers don't block the system - they return an error message to the manager
   - If a node crushes - visibility time in the `ManagerToWorkerQueue` was set to 45 minutes so another worker will take its place

---

## Persistence

1. **SQS Visibility Timeout**:
   - Worker messages have a 45 min visibility timeout
   - If a worker crashes, the message becomes visible again for another worker
   - Ensures no work is lost if a worker fails

2. **Error Handling in Workers**:
   - Workers catch all exceptions and continue processing
   - Failed tasks send error messages back to Manager (not lost)

---

## Threads

   **Manager uses 2 threads**:
   - `InputHandler`: Processes incoming tasks from LocalApplication
   - `OutputHandler`: Processes completed results from Workers
   
   **Why this is good:**
   - Allows concurrent handling of new tasks and results
   - Neither handler blocks the other
   - Uses `ConcurrentHashMap` for thread-safe task tracking

**Thread-safety measures:**
- `ConcurrentHashMap` for shared state
- `synchronized` blocks for termination flags

---

## Multiple Clients

**The system fully supports concurrent clients:**

1. **Unique Identification**:
   - Each client generates a UUID `appId` at startup
   - All messages and files are tagged with this `appId`

2. **Message Filtering**:
   - LocalApplication only accepts messages containing its `appId`
   - Other messages are released back to the queue (visibility timeout set to 0)

3. **Resource Isolation**:
   - Each client's files are in separate S3 prefixes: `inputs/<appId>/`, `outputs/<appId>/`
   - No file name collisions between clients

4. **Task Tracking**:
   - Manager tracks pending tasks per `appId`

---

## Termination Process

**Termination Sequence:**

1. Client sends `terminate` message to Manager
2. `InputHandler` receives termination:
   - Sets termination flag
   - Processes remaining messages in queue (responds with "service terminated")
   - Signals completion to `OutputHandler`

3. `OutputHandler` continues until:
   - All workers are finished
   - `InputHandler` has finished
   - No more messages in worker queue

4. Cleanup:
   - Wait 5 seconds for message propagation
   - Delete all SQS queues
   - Delete all S3 objects (except processed/ folder for result access)
   - Terminate all worker EC2 instances
   - Terminate Manager EC2 instance

---

## System Limitations

**Optimizations implemented:**
- Long polling (20 seconds) to reduce API calls and busy waiting
- Batch instance creation when multiple workers needed
- Manager threads run independently to maximize throughput

**Respecting limitations:**
- Maximum 8 workers (AWS Academy limit)
- T3_LARGE instances for sufficient memory for NLP processing

---

## Worker Load Distribution

**Work is evenly distributed:**
- Workers request work when ready
- Busy workers automatically get fewer messages
- Idle workers immediately pick up new work

**No worker slacking because:**
- Workers run continuous loop with no idle delays
- Long polling ensures immediate message pickup

---

## Separation of Concerns

**LocalApplication:**
- User interface and input validation
- Resource creation (creates Manager if needed)
- Task submission and result retrieval
- Does not process data or manage workers

**Manager:**
- Task distribution and worker management
- Input parsing and validation
- Output aggregation and merging
- Does not perform NLP analysis

**Workers:**
- NLP processing only
- File download and result upload
- Does not make decisions about task distribution
- Does not interact with users

---

## Distributed Understanding

**Asynchronous design:**
- LocalApplication waits for results but doesn't block Manager
- Manager threads work independently
- No dependencies between workers
- All communication via SQS queues (fire and forget)

**Blocking points:**
- LocalApplication blocks waiting for its specific result
- OutputHandler thread awaits InputHandler thread at termination

---

## File Structure

```
AWS-1/
├── buildAll.bat                 # Build all projects
├── cleanAll.bat                 # Clean all projects
├── README                       # This file
│
├── LocalApplication/
│   ├── pom.xml
│   ├── data/
│   │   └── input-sample.txt     # Sample input file
│   │   └── output.txt           # Sample output file
│   └── src/main/java/
│       ├── LocalApplication.java
│       └── AWS.java
│
├── Manager/
│   ├── pom.xml
│   └── src/main/java/
│       ├── Manager.java
│       ├── InputHandler.java
│       ├── OutputHandler.java
│       └── AWS.java
│
└── Worker/
    ├── pom.xml
    └── src/main/java/
        ├── Worker.java
        ├── TextAnalyzer.java
        └── AWS.java
```

---

## Message Formats

### LocalApplication → Manager
- New task: `new task:<s3Key>:<appId>`
- Termination: `terminate`
- Cancel: `cancel operation:<appId>`

### Manager → LocalApplication
- Done: `done:<outputS3Key>:<appId>`

### Manager → Worker
- Work: `<ANALYSIS_TYPE> <URL> <appId>`

### Worker → Manager
- Success: `<ANALYSIS_TYPE> <URL> <resultS3Key> <appId>`
- Error: `<ANALYSIS_TYPE> <URL> ERROR:<description> <appId>`
