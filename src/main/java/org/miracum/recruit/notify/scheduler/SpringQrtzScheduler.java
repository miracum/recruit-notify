package org.miracum.recruit.notify.scheduler;

import static net.logstash.logback.argument.StructuredArguments.kv;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.quartz.SchedulerFactoryBean;

/** Quartz Scheduler initializing scheduled job for each defined timer in app config. */
@Configuration
public class SpringQrtzScheduler {
  private static final Logger LOG = LoggerFactory.getLogger(SpringQrtzScheduler.class);

  private final UserConfig config;

  @Autowired
  SpringQrtzScheduler(UserConfig config) {
    this.config = config;
  }

  @Bean
  public Scheduler scheduler(SchedulerFactoryBean factory) throws SchedulerException {
    var schedulerData = createSchedulerInfo(config.getSchedules());
    var scheduler = factory.getScheduler();

    for (var scheduleItem : schedulerData) {
      var trigger =
          createTrigger(
              scheduleItem.getJobName(),
              scheduleItem.getGroupName(),
              scheduleItem.getCronExpression());

      var job = createJobDetail(scheduleItem.getJobName(), scheduleItem.getGroupName());

      LOG.debug(
          "scheduling {} at {}",
          kv("job", job.getKey()),
          kv("cron", scheduleItem.getCronExpression(), "{0}=\"{1}\""));
      scheduler.scheduleJob(job, trigger);
    }

    LOG.debug("starting scheduler instance");
    scheduler.start();

    return scheduler;
  }

  private JobDetail createJobDetail(String jobName, String groupName) {
    return JobBuilder.newJob(NotifyMessageSchedulerJob.class)
        .withIdentity(jobName, groupName)
        .storeDurably(true)
        .build();
  }

  private Trigger createTrigger(
      String triggerName, String triggerGroup, CronExpression cronExpression) {
    return TriggerBuilder.newTrigger()
        .withIdentity(triggerName, triggerGroup)
        .withSchedule(CronScheduleBuilder.cronSchedule(cronExpression))
        .build();
  }

  private List<SchedulerData> createSchedulerInfo(Map<String, CronExpression> timerList) {
    var schedulerData = new ArrayList<SchedulerData>();
    for (var cronTimer : timerList.entrySet()) {
      var schedulerDataItem = new SchedulerData();
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
