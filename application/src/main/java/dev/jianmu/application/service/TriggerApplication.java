package dev.jianmu.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.pagehelper.PageInfo;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import dev.jianmu.application.exception.DataNotFoundException;
import dev.jianmu.el.ElContext;
import dev.jianmu.infrastructure.mybatis.trigger.WebRequestRepositoryImpl;
import dev.jianmu.infrastructure.quartz.PublishJob;
import dev.jianmu.project.repository.ProjectRepository;
import dev.jianmu.secret.aggregate.CredentialManager;
import dev.jianmu.trigger.aggregate.Trigger;
import dev.jianmu.trigger.aggregate.WebRequest;
import dev.jianmu.trigger.aggregate.Webhook;
import dev.jianmu.trigger.event.TriggerEvent;
import dev.jianmu.trigger.event.TriggerEventParameter;
import dev.jianmu.trigger.repository.TriggerEventRepository;
import dev.jianmu.trigger.repository.TriggerRepository;
import dev.jianmu.workflow.aggregate.parameter.Parameter;
import dev.jianmu.workflow.el.EvaluationContext;
import dev.jianmu.workflow.el.EvaluationResult;
import dev.jianmu.workflow.el.Expression;
import dev.jianmu.workflow.el.ExpressionLanguage;
import dev.jianmu.workflow.repository.ParameterRepository;
import lombok.extern.slf4j.Slf4j;
import org.quartz.*;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.MultiValueMap;
import org.springframework.web.util.UriComponentsBuilder;

import javax.servlet.http.HttpServletRequest;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static java.util.stream.Collectors.*;

/**
 * @class: TriggerApplication
 * @description: TriggerApplication
 * @author: Ethan Liu
 * @create: 2021-11-10 11:15
 */
@Service
@Slf4j
public class TriggerApplication {
    private final TriggerRepository triggerRepository;
    private final TriggerEventRepository triggerEventRepository;
    private final ParameterRepository parameterRepository;
    private final ProjectRepository projectRepository;
    private final WebRequestRepositoryImpl webRequestRepositoryImpl;
    private final CredentialManager credentialManager;
    private final Scheduler quartzScheduler;
    private final ApplicationEventPublisher publisher;
    private final ObjectMapper objectMapper;
    // 表达式计算服务
    private final ExpressionLanguage expressionLanguage;

    public TriggerApplication(
            TriggerRepository triggerRepository,
            TriggerEventRepository triggerEventRepository,
            ParameterRepository parameterRepository,
            ProjectRepository projectRepository,
            WebRequestRepositoryImpl webRequestRepositoryImpl,
            CredentialManager credentialManager,
            Scheduler quartzScheduler,
            ApplicationEventPublisher publisher,
            ObjectMapper objectMapper,
            ExpressionLanguage expressionLanguage) {
        this.triggerRepository = triggerRepository;
        this.triggerEventRepository = triggerEventRepository;
        this.parameterRepository = parameterRepository;
        this.projectRepository = projectRepository;
        this.webRequestRepositoryImpl = webRequestRepositoryImpl;
        this.credentialManager = credentialManager;
        this.quartzScheduler = quartzScheduler;
        this.publisher = publisher;
        this.objectMapper = objectMapper;
        this.expressionLanguage = expressionLanguage;
    }

    @Transactional
    public void trigger(String triggerId) {
        var trigger = this.triggerRepository.findByTriggerId(triggerId)
                .orElseThrow(() -> new DataNotFoundException("未找到触发器"));
        var evt = TriggerEvent.Builder.aTriggerEvent()
                .projectId(trigger.getProjectId())
                .triggerId(trigger.getId())
                .triggerType(trigger.getType().name())
                .build();
        this.triggerEventRepository.save(evt);
        this.publisher.publishEvent(evt);
    }

    @Transactional
    public void trigger(
            List<TriggerEventParameter> eventParameters,
            List<Parameter> parameters,
            WebRequest webRequest
    ) {
        // 过滤SECRET类型参数不保存
        var eventParametersClean = eventParameters.stream()
                .filter(triggerEventParameter -> !triggerEventParameter.getType().equals("SECRET"))
                .collect(Collectors.toList());
        var parametersClean = parameters.stream()
                .filter(parameter -> !(parameter.getType() == Parameter.Type.SECRET))
                .collect(Collectors.toList());
        var event = TriggerEvent.Builder.aTriggerEvent()
                .triggerId(webRequest.getTriggerId())
                .projectId(webRequest.getProjectId())
                .webRequestId(webRequest.getId())
                .payload(webRequest.getPayload())
                .parameters(eventParametersClean)
                .triggerType(Trigger.Type.WEBHOOK.name())
                .build();
        this.parameterRepository.addAll(parametersClean);
        this.triggerEventRepository.save(event);
        this.publisher.publishEvent(event);
    }

    @Transactional
    public void saveOrUpdate(String projectId, Webhook webhook) {
        this.triggerRepository.findByProjectId(projectId)
                .ifPresentOrElse(trigger -> {
                    trigger.setType(Trigger.Type.WEBHOOK);
                    trigger.setWebhook(webhook);
                    this.triggerRepository.updateById(trigger);
                }, () -> {
                    var trigger = Trigger.Builder.aTrigger()
                            .projectId(projectId)
                            .type(Trigger.Type.WEBHOOK)
                            .webhook(webhook)
                            .build();
                    this.triggerRepository.add(trigger);
                });
    }

    @Transactional
    public void saveOrUpdate(String projectId, String schedule) {
        this.triggerRepository.findByProjectId(projectId)
                .ifPresentOrElse(trigger -> {
                    try {
                        // 更新schedule
                        trigger.setSchedule(schedule);
                        trigger.setType(Trigger.Type.CRON);
                        // 停止触发器
                        this.quartzScheduler.pauseTrigger(TriggerKey.triggerKey(trigger.getId()));
                        // 卸载任务
                        this.quartzScheduler.unscheduleJob(TriggerKey.triggerKey(trigger.getId()));
                        // 删除任务
                        this.quartzScheduler.deleteJob(JobKey.jobKey(trigger.getId()));
                        var jobDetail = this.createJobDetail(trigger);
                        var cronTrigger = this.createCronTrigger(trigger);
                        this.quartzScheduler.scheduleJob(jobDetail, cronTrigger);
                    } catch (SchedulerException e) {
                        log.error("触发器更新失败: {}", e.getMessage());
                        throw new RuntimeException("触发器更新失败");
                    }
                    this.triggerRepository.updateById(trigger);
                }, () -> {
                    var trigger = Trigger.Builder.aTrigger()
                            .projectId(projectId)
                            .type(Trigger.Type.CRON)
                            .schedule(schedule)
                            .build();
                    try {
                        var jobDetail = this.createJobDetail(trigger);
                        var cronTrigger = this.createCronTrigger(trigger);
                        quartzScheduler.scheduleJob(jobDetail, cronTrigger);
                    } catch (SchedulerException e) {
                        log.error("触发器加载失败: {}", e.getMessage());
                        throw new RuntimeException("触发器加载失败");
                    }
                    this.triggerRepository.add(trigger);
                });
    }

    @Transactional
    public void deleteByProjectId(String projectId) {
        this.triggerRepository.findByProjectId(projectId)
                .ifPresent(trigger -> {
                    try {
                        // 停止触发器
                        quartzScheduler.pauseTrigger(TriggerKey.triggerKey(trigger.getId()));
                        // 卸载任务
                        quartzScheduler.unscheduleJob(TriggerKey.triggerKey(trigger.getId()));
                        // 删除任务
                        quartzScheduler.deleteJob(JobKey.jobKey(trigger.getId()));
                    } catch (SchedulerException e) {
                        log.error("触发器删除失败: {}", e.getMessage());
                        throw new RuntimeException("触发器删除失败");
                    }
                    this.triggerRepository.deleteById(trigger.getId());
                });
    }

    public String getNextFireTime(String projectId) {
        var triggerId = this.triggerRepository.findByProjectId(projectId)
                .filter(trigger -> trigger.getType() == Trigger.Type.CRON)
                .map(Trigger::getId)
                .orElse("");
        if (triggerId.isBlank()) {
            return triggerId;
        }
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            var schedulerTrigger = this.quartzScheduler.getTrigger(TriggerKey.triggerKey(triggerId));
            if (schedulerTrigger != null) {
                var date = schedulerTrigger.getNextFireTime();
                return sdf.format(date);
            }
            return "";
        } catch (SchedulerException e) {
            log.info("未找到触发器： {}", e.getMessage());
            throw new RuntimeException("未找到触发器");
        }
    }

    public void startTriggers() {
        var triggers = this.triggerRepository.findCronTriggerAll();
        triggers.forEach(trigger -> {
            var cronTrigger = this.createCronTrigger(trigger);
            var jobDetail = this.createJobDetail(trigger);
            try {
                quartzScheduler.scheduleJob(jobDetail, cronTrigger);
            } catch (SchedulerException e) {
                log.error("触发器加载失败: {}", e.getMessage());
                throw new RuntimeException("触发器加载失败");
            }
        });
        try {
            quartzScheduler.start();
        } catch (SchedulerException e) {
            log.error("触发器启动失败: {}", e.getMessage());
            throw new RuntimeException("触发器启动失败");
        }
    }

    private CronTrigger createCronTrigger(Trigger trigger) {
        var builder = CronScheduleBuilder.cronSchedule(trigger.getSchedule());
        return TriggerBuilder.newTrigger()
                .withIdentity(TriggerKey.triggerKey(trigger.getId()))
                .usingJobData("triggerId", trigger.getId())
                .withSchedule(builder)
                .build();
    }

    private JobDetail createJobDetail(Trigger trigger) {
        return JobBuilder.newJob()
                .withIdentity(JobKey.jobKey(trigger.getId()))
                .ofType(PublishJob.class)
                .build();
    }

    public TriggerEvent findTriggerEvent(String triggerEventId) {
        var event = this.triggerEventRepository.findById(triggerEventId)
                .orElseThrow(() -> new DataNotFoundException("未找到该触发事件"));
        return event;
    }

    public PageInfo<WebRequest> findWebRequestPage(int pageNum, int pageSize) {
        return this.webRequestRepositoryImpl.findPage(pageNum, pageSize);
    }

    public String getWebhookUrl(String projectId) {
        var project = this.projectRepository.findById(projectId)
                .orElseThrow(() -> new DataNotFoundException("未找到该项目"));
        return "/webhook/" + URLEncoder.encode(project.getWorkflowName(), StandardCharsets.UTF_8);
    }

    public void receiveHttpEvent(String projectName, HttpServletRequest request, String contentType) {
        var webRequest = this.createWebRequest(request, contentType);
        var project = this.projectRepository.findByName(projectName)
                .orElseThrow(() -> {
                    webRequest.setStatusCode(WebRequest.StatusCode.NOT_FOUND);
                    webRequest.setErrorMsg("未找到项目: " + projectName);
                    this.webRequestRepositoryImpl.add(webRequest);
                    return new DataNotFoundException("未找到项目: " + projectName);
                });
        webRequest.setProjectId(project.getId());
        webRequest.setWorkflowRef(project.getWorkflowRef());
        webRequest.setWorkflowVersion(project.getWorkflowVersion());
        var trigger = this.triggerRepository.findByProjectId(project.getId())
                .orElseThrow(() -> {
                    webRequest.setStatusCode(WebRequest.StatusCode.NOT_FOUND);
                    webRequest.setErrorMsg("项目：" + projectName + " 未找到触发器");
                    this.webRequestRepositoryImpl.add(webRequest);
                    return new DataNotFoundException("项目：" + projectName + " 未找到触发器");
                });
        webRequest.setTriggerId(trigger.getId());
        if (trigger.getType() != Trigger.Type.WEBHOOK) {
            webRequest.setStatusCode(WebRequest.StatusCode.NOT_ACCEPTABLE);
            webRequest.setErrorMsg("项目：" + projectName + " 未找到触发器");
            this.webRequestRepositoryImpl.add(webRequest);
            throw new IllegalArgumentException("项目：" + projectName + "触发器类型错误");
        }
        // 创建表达式上下文
        var context = new ElContext();
        // 提取参数
        var webhook = trigger.getWebhook();
        List<TriggerEventParameter> eventParameters = new ArrayList<>();
        List<Parameter> parameters = new ArrayList<>();
        if (webhook.getParam() != null) {
            webhook.getParam().forEach(webhookParameter -> {
                Parameter<?> parameter = Parameter.Type
                        .getTypeByName(webhookParameter.getType())
                        .newParameter(this.extractParameter(webRequest.getPayload(), webhookParameter.getExp()));
                var eventParameter = TriggerEventParameter.Builder.aTriggerParameter()
                        .name(webhookParameter.getName())
                        .type(webhookParameter.getType())
                        .value(parameter.getStringValue())
                        .parameterId(parameter.getId())
                        .build();
                parameters.add(parameter);
                eventParameters.add(eventParameter);
                context.add("trigger", eventParameter.getName(), parameter);
            });
        }
        // 验证Auth
        if (webhook.getAuth() != null) {
            var auth = webhook.getAuth();
            var authToken = this.calculateExp(auth.getToken(), context);
            var authValue = this.findSecret(auth.getValue());
            if (authToken.getType() != Parameter.Type.STRING) {
                log.warn("Auth Token表达式计算错误");
                webRequest.setStatusCode(WebRequest.StatusCode.UNAUTHORIZED);
                webRequest.setErrorMsg("Auth Token表达式计算错误");
                this.webRequestRepositoryImpl.add(webRequest);
                return;
            }
            if (!authToken.getValue().equals(authValue)) {
                log.warn("Webhook密钥不匹配");
                webRequest.setStatusCode(WebRequest.StatusCode.UNAUTHORIZED);
                webRequest.setErrorMsg("Webhook密钥不匹配");
                this.webRequestRepositoryImpl.add(webRequest);
                return;
            }
        }
        // 验证Matcher
        if (webhook.getMatcher() != null) {
            var res = this.calculateExp(webhook.getMatcher(), context);
            if (res.getType() != Parameter.Type.BOOL || !((Boolean) res.getValue())) {
                log.warn("Match计算不匹配，计算结果为：{}", res.getStringValue());
                webRequest.setStatusCode(WebRequest.StatusCode.NOT_ACCEPTABLE);
                webRequest.setErrorMsg("Match计算不匹配，计算结果为：" + res.getStringValue());
                this.webRequestRepositoryImpl.add(webRequest);
                return;
            }
        }
        this.webRequestRepositoryImpl.add(webRequest);
        this.trigger(eventParameters, parameters, webRequest);
    }

    private String findSecret(String secretExp) {
        var secret = this.isSecret(secretExp);
        if (secret == null) {
            throw new IllegalArgumentException("密钥参数格式错误：" + secretExp);
        }
        // 处理密钥类型参数, 获取值后转换为String类型参数
        var strings = secret.split("\\.");
        var kv = this.credentialManager.findByNamespaceNameAndKey(strings[0], strings[1])
                .orElseThrow(() -> new DataNotFoundException("未找到密钥"));
        return kv.getValue();
    }

    private String isSecret(String paramValue) {
        Pattern pattern = Pattern.compile("^\\(\\(([a-zA-Z0-9_-]+\\.*[a-zA-Z0-9_-]+)\\)\\)$");
        Matcher matcher = pattern.matcher(paramValue);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    private Parameter<?> calculateExp(String exp, EvaluationContext context) {
        // 密钥类型单独处理
        var secret = this.isSecret(exp);
        if (secret != null) {
            return Parameter.Type.SECRET.newParameter(secret);
        }
        String el;
        if (isEl(exp)) {
            el = exp;
        } else {
            el = "`" + exp + "`";
        }
        // 计算参数表达式
        Expression expression = expressionLanguage.parseExpression(el);
        EvaluationResult evaluationResult = expressionLanguage.evaluateExpression(expression, context);
        if (evaluationResult.isFailure()) {
            var errorMsg = "表达式：" + exp +
                    " 计算错误: " + evaluationResult.getFailureMessage();
            throw new RuntimeException(errorMsg);
        }
        return evaluationResult.getValue();
    }

    private boolean isEl(String paramValue) {
        Pattern pattern = Pattern.compile("^\\(");
        Matcher matcher = pattern.matcher(paramValue);
        return matcher.lookingAt();
    }

    private Object extractParameter(String payload, String exp) {
        if (exp.startsWith("$.header.")) {
            exp = exp.toLowerCase(Locale.ROOT);
        }
        Object document = Configuration.defaultConfiguration().jsonProvider().parse(payload);
        try {
            return JsonPath.read(document, exp);
        } catch (PathNotFoundException e) {
            return null;
        }
    }

    private WebRequest createWebRequest(HttpServletRequest request, String contentType) {
        try {
            // Get body
            var body = request.getReader()
                    .lines()
                    .collect(Collectors.joining(System.lineSeparator()));
            // Create root node
            var root = this.objectMapper.createObjectNode();
            // Headers node
            Map<String, List<String>> headers = Collections.list(request.getHeaderNames())
                    .stream()
                    .collect(Collectors.toMap(
                            Function.identity(),
                            h -> Collections.list(request.getHeaders(h))
                    ));
            var headerNode = root.putObject("header");
            headers.forEach((key, value) -> {
                if (value.size() > 1) {
                    var item = headerNode.putArray(key);
                    value.forEach(item::add);
                } else {
                    headerNode.put(key, value.get(0));
                }
            });
            // Query String node
            var url = request.getRequestURL().toString();
            var queryString = request.getQueryString();
            MultiValueMap<String, String> parameters =
                    UriComponentsBuilder.fromUriString(url + "?" + queryString).build().getQueryParams();
            var queryNode = root.putObject("query");
            parameters.forEach((key, value) -> {
                if ("null".equals(key)) {
                    return;
                }
                if (value.size() > 1) {
                    var item = queryNode.putArray(key);
                    value.forEach(item::add);
                } else {
                    queryNode.put(key, value.get(0));
                }
            });
            // Body node
            var bodyNode = root.putObject("body");
            // Body Json node
            if (contentType.startsWith("application/json")) {
                var bodyJson = this.objectMapper.readTree(body);
                bodyNode.set("json", bodyJson);
            }
            // Body Form node
            if (contentType.startsWith("application/x-www-form-urlencoded")) {
                var formNode = bodyNode.putObject("form");
                var formMap = Pattern.compile("&")
                        .splitAsStream(body)
                        .map(s -> Arrays.copyOf(s.split("=", 2), 2))
                        .collect(groupingBy(s -> decode(s[0]), mapping(s -> decode(s[1]), toList())));
                formMap.forEach((key, value) -> {
                    if (value.size() > 1) {
                        var item = formNode.putArray(key);
                        value.forEach(item::add);
                    } else {
                        formNode.put(key, value.get(0));
                    }
                });
            }
            // Body Text node
            if (contentType.startsWith("text/plain")) {
                bodyNode.put("text", body);
            }
            return WebRequest.Builder.aWebRequest()
                    .userAgent(request.getHeader("User-Agent"))
                    .payload(root.toString())
                    .statusCode(WebRequest.StatusCode.OK)
                    .build();
        } catch (Exception e) {
            return WebRequest.Builder.aWebRequest()
                    .userAgent(request.getHeader("User-Agent"))
                    .statusCode(WebRequest.StatusCode.UNKNOWN)
                    .errorMsg(e.getMessage())
                    .build();
        }
    }

    private static String decode(final String encoded) {
        return Optional.ofNullable(encoded)
                .map(e -> URLDecoder.decode(e, StandardCharsets.UTF_8))
                .orElse(null);
    }
}
