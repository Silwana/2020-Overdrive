package za.co.entelect.challenge.botrunners.local.handlers;

import org.apache.commons.exec.ExecuteStreamHandler;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

public class CommandHandler implements ExecuteStreamHandler {

    private static final Logger log = LogManager.getLogger(CommandHandler.class);

    private final int timeoutInMilliseconds;

    private OutputStream botInputStream;
    private InputStream botErrorStream;
    private InputStream botOutputStream;

    private BotInputHandler botInputHandler;
    private BotErrorHandler botErrorHandler;

    private Thread botInputThread;
    private Thread botErrorThread;

    private final ReentrantLock reentrantLock = new ReentrantLock(true);
    private final Condition botReadyCondition = reentrantLock.newCondition();
    private final Condition commandSignalCondition = reentrantLock.newCondition();

    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean stopped = new AtomicBoolean(false);

    public CommandHandler(int timeoutInMilliseconds) {
        this.timeoutInMilliseconds = timeoutInMilliseconds;
    }

    @Override
    public void setProcessInputStream(OutputStream os) {
        this.botInputStream = os;
    }

    @Override
    public void setProcessErrorStream(InputStream is) {
        this.botErrorStream = is;
    }

    @Override
    public void setProcessOutputStream(InputStream is) {
        botOutputStream = is;
    }

    @Override
    public void start() {

        reentrantLock.lock();
        started.set(true);

        botInputHandler = new BotInputHandler(botOutputStream, reentrantLock, commandSignalCondition);
        botErrorHandler = new BotErrorHandler(botErrorStream);

        botInputThread = new Thread(botInputHandler);
        botErrorThread = new Thread(botErrorHandler);

        botInputThread.start();
        botErrorThread.start();

        //Wait for bot streams to start listening
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            //We can ignore this
        }

        botReadyCondition.signal();
        reentrantLock.unlock();
    }

    @Override
    public void stop() {
        botInputThread.interrupt();
        botErrorThread.interrupt();

        stopped.set(true);
        reentrantLock.lock();
        commandSignalCondition.signal();
        reentrantLock.unlock();
    }

    public String getBotCommand() {
        if (!stopped.get()) {
            reentrantLock.lock();
            try {
                commandSignalCondition.await(timeoutInMilliseconds, TimeUnit.MILLISECONDS);
                if (!stopped.get()) {
                    return botInputHandler.getLastReceivedCommand();
                }
            } catch (InterruptedException e) {
                log.error("Bot exceeded time limit", e);
            } finally {
                reentrantLock.unlock();
            }
        }

        return "";
    }

    public String getBotError() {
        if (!stopped.get()) {
            reentrantLock.lock();
            try {
                if (!stopped.get()) {
                    return botErrorHandler.getLastError();
                }
            } finally {
                reentrantLock.unlock();
            }
        }

        return "";
    }

    public void signalNewRound(int round) {

        reentrantLock.lock();

        if (!started.get()) {
            botReadyCondition.awaitUninterruptibly();
        }

        try {
            botInputHandler.setCurrentRound(round);

            if (!stopped.get()) {
                botInputStream.write(Integer.toString(round).getBytes());
                botInputStream.write(System.lineSeparator().getBytes());
                botInputStream.flush();
            }
        } catch (IOException e) {
            log.error("Failed to notify bot of new round", e);
        }

        reentrantLock.unlock();
    }
}
