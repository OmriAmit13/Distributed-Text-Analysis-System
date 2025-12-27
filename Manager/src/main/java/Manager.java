import java.util.concurrent.ConcurrentHashMap;

public class Manager {

    private static final AWS aws = AWS.getInstance();
    private static final ConcurrentHashMap<String, Integer> filesInProcess = new ConcurrentHashMap<>();
    private static Boolean terminate = false;
    private static Boolean inputHandlerFinished = false;
    private static int n;

    public static void main(String[] args) {
        if (args.length > 0) {
            n = Integer.parseInt(args[0]);
        }

        aws.getEC2Tags();
        aws.createWorkerQueues();

        Thread inputHandlerThread = new Thread(new InputHandler());
        Thread outputHandlerThread = new Thread(new OutputHandler());

        inputHandlerThread.start();
        outputHandlerThread.start();

        try {
            inputHandlerThread.join();
            outputHandlerThread.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    public static void addNewTask(String appId, int numOfFiles) {
        filesInProcess.put(appId, numOfFiles);
    }

    public static boolean fileProcessed(String appId) {
        filesInProcess.computeIfPresent(appId, (key, val) -> val - 1);
        if (filesInProcess.get(appId) == 0) {
            filesInProcess.remove(appId);
            return true;
        }
        return false;
    }

    public static boolean hasPendingTasks() {
        return !filesInProcess.isEmpty();
    }

    public static void terminate() {
        synchronized (terminate) {
            terminate = true;
        }
    }

    public static boolean isTerminated() {
        synchronized (terminate) {
            return terminate;
        }
    }

    public static void finishInputHandler() {
        synchronized (inputHandlerFinished) {
            inputHandlerFinished = true;
        }
    }

    public static boolean inputHandlerFinished() {
        synchronized (inputHandlerFinished) {
            return inputHandlerFinished;
        }
    }

    public static int getN() {
        return n;
    }
}
