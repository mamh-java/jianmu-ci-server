package dev.jianmu.infrastructure.storage;

import dev.jianmu.infrastructure.SseTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicLong;

/**
 * @author Ethan Liu
 * @class FileSystemStorageService
 * @description 文件系统实现类
 * @create 2021-04-05 21:01
 */
@Service
public class FileSystemStorageService implements StorageService, ApplicationRunner {
    private static final Logger logger = LoggerFactory.getLogger(FileSystemStorageService.class);
    private static final String LogfilePostfix = ".log";
    private static final String webhookFilePostfix = ".json";
    // For SSE
    private final SseTemplate template;
    private final MonitoringFileService monitoringFileService;
    private static final AtomicLong COUNTER = new AtomicLong(0);

    private final Path rootLocation;
    private final Path webhookRootLocation;

    public FileSystemStorageService(SseTemplate template, MonitoringFileService monitoringFileService, StorageProperties properties) {
        this.template = template;
        this.monitoringFileService = monitoringFileService;
        this.rootLocation = Paths.get("ci", properties.getLogfilePath());
        this.webhookRootLocation = Paths.get("ci", properties.getWebhookFilePath());
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        this.init();
    }

    @Override
    public void init() {
        try {
            Files.createDirectories(rootLocation);
            Files.createDirectories(webhookRootLocation);
        } catch (FileAlreadyExistsException e) {
            logger.info("the directory already exits");
        } catch (IOException e) {
            throw new StorageException("Could not initialize storage", e);
        }
    }

    @Override
    public BufferedWriter writeLog(String LogFileName) {
        try {
            return new BufferedWriter(
                    new FileWriter(this.rootLocation + File.separator + LogFileName + LogfilePostfix, StandardCharsets.UTF_8, true)
            );
        } catch (IOException e) {
            throw new StorageException("Could not create log file", e);
        }
    }

    @Override
    public SseEmitter readLog(String logFileName) {
        var fullName = logFileName + LogfilePostfix;
        this.monitoringFileService.listen(fullName, (file -> {
            try (var stream = Files.lines(file)) {
                stream.skip(COUNTER.get())
                        .forEach(line ->
                                template.broadcast(fullName, SseEmitter.event()
                                        .id(String.valueOf(COUNTER.incrementAndGet()))
                                        .data(line)));
            } catch (IOException e) {
                e.printStackTrace();
            }
        }));
        return this.template.newSseEmitter(fullName);
    }

    @Override
    public File logFile(String LogFileName) {
        return new File(this.rootLocation + File.separator + LogFileName + LogfilePostfix);
    }

    @Override
    public File workflowLogFile(String LogFileName) {
        return new File("ci" + File.separator + "workflow_log" + File.separator + LogFileName + LogfilePostfix);
    }

    @Override
    public BufferedWriter writeWebhook(String webhookFileName) {
        try {
            return new BufferedWriter(
                    new FileWriter(this.webhookRootLocation + File.separator + webhookFileName + webhookFilePostfix, StandardCharsets.UTF_8, true)
            );
        } catch (IOException e) {
            throw new StorageException("Could not create webhook file", e);
        }
    }

    @Override
    public String readWebhook(String webhookFileName) {
        try {
            var bufferedReader = new BufferedReader(new FileReader(this.webhookRootLocation + File.separator + webhookFileName + webhookFilePostfix, StandardCharsets.UTF_8));
            var stringBuilder = new StringBuilder();
            String line;
            while ((line = bufferedReader.readLine()) != null) {
                stringBuilder.append(line);
            }
            return stringBuilder.toString();
        } catch (IOException e) {
            logger.warn("获取webhook文件异常: {}", e.getMessage());
            throw new RuntimeException("webhook文件不存在");
        }
    }
}
