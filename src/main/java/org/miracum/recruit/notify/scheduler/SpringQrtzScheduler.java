package org.miracum.recruit.notify.scheduler;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.annotation.PostConstruct;
import org.miracum.recruit.notify.logging.LogMethodCalls;
import org.miracum.recruit.notify.mailconfig.UserConfig;
import org.quartz.CronExpression;
import org.quartz.CronScheduleBuilder;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.quartz.SchedulerFactoryBean;
import org.springframework.scheduling.quartz.SpringBeanJobFactory;

/** Quartz Scheduler initializing scheduled job for each defined timer in app config. */
@Configuration
public class SpringQrtzScheduler {

  private static final Logger LOG = LoggerFactory.getLogger(SpringQrtzScheduler.class);

  private final UserConfig config;

  private final ApplicationContext applicationContext;

  @Autowired
  SpringQrtzScheduler(UserConfig config, ApplicationContext applicationContext) {
    this.config = config;
    this.applicationContext = applicationContext;
  }

  @PostConstruct
  public void init() {
    LOG.info("init scheduler");
  }

  /** Spring bean job factory to return job factory. */
  @Bean
  public SpringBeanJobFactory springBeanJobFactory() {
    AutoWiringSpringBeanJobFactory jobFactory = new AutoWiringSpringBeanJobFactory();
    LOG.debug("Configuring Job factory");

    jobFactory.setApplicationContext(applicationContext);
    return jobFactory;
  }

  @Bean("schedulerFactoryBean")
  public SchedulerFactoryBean schedulerFactoryBean() {
    SchedulerFactoryBean factory = new SchedulerFactoryBean();
    factory.setJobFactory(springBeanJobFactory());
    return factory;
  }

  /** Create scheduler for each defined trigger. */
  @Bean
  @LogMethodCalls
  public List<Scheduler> scheduler(@Qualifier("schedulerFactoryBean") SchedulerFactoryBean factory)
      throws SchedulerException {

    List<Scheduler> result = new ArrayList<>();

    List<SchedulerData> schedulerData = createSchedulerInfo(config.getSchedules());

    for (SchedulerData scheduleItem : schedulerData) {

      LOG.debug("schedule item: {}", scheduleItem.getJobName());

      Trigger trigger =
          trigger(
              scheduleItem.getJobName(),
              scheduleItem.getGroupName(),
              scheduleItem.getCronExpression());
      JobDetail job = jobDetail(scheduleItem.getJobName(), scheduleItem.getGroupName());

      LOG.debug("create scheduler instance");
      Scheduler scheduler = factory.getScheduler();

      LOG.debug("schedule job by job and trigger");
      scheduler.scheduleJob(job, trigger);

      LOG.debug("start scheduler instance");
      scheduler.start();

      result.add(scheduler);
    }

    return result;
  }

  @LogMethodCalls
  private JobDetail jobDetail(String jobName, String groupName) {

    LOG.debug("job item {}", jobName);

    return JobBuilder.newJob(NotifyMessageSchedulerJob.class)
        .withIdentity(jobName, groupName)
        .build();
  }

  @LogMethodCalls
  private Trigger trigger(String triggerName, String triggerGroup, CronExpression cronExpression) {

    LOG.debug("trigger item {}", triggerName);

    return TriggerBuilder.newTrigger()
        .withIdentity(triggerName, triggerGroup)
        .withSchedule(CronScheduleBuilder.cronSchedule(cronExpression))
        .build();
  }

  @LogMethodCalls
  private List<SchedulerData> createSchedulerInfo(Map<String, CronExpression> timerList) {

    List<SchedulerData> schedulerData = new ArrayList<>();

    for (var cronTimer : timerList.entrySet()) {

      LOG.debug("timer name \"{}\" was found", cronTimer.getKey());
      SchedulerData schedulerDataItem = new SchedulerData();

      schedulerDataItem.setJobName(cronTimer.getKey());
      schedulerDataItem.setTriggerName(cronTimer.getKey());

      schedulerDataItem.setCronExpression(cronTimer.getValue());

      schedulerDataItem.setGroupName("notify");
      schedulerDataItem.setTriggerGroup("notify");

      schedulerData.add(schedulerDataItem);
    }
    return schedulerData;
  }
}
