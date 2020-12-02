package org.miracum.recruit.notify.logging;

import java.util.Arrays;
import java.util.List;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Aspect oriented programming (AOP) - Logging. */
@Aspect
@Component
public class LogImportantFunctionAspect {

  private final Logger log = LoggerFactory.getLogger(this.getClass());

  /** Create concatenated string from object list. */
  public static String listToString(List<Object> list) {
    var sb = new StringBuilder();
    if (list != null) {
      for (int i = 0; i < list.size(); i++) {
        Object o = list.get(i);
        String s = o == null ? "<null>" : o.toString();
        sb.append(i == 0 ? s : ", " + s);
      }
    }
    return sb.toString();
  }

  public static String listToString(Object[] array) {
    return listToString(Arrays.asList(array));
  }

  /**
   * Entering and exit function will be logged automatically on info level with package name, method
   * name and arguments.
   */
  @Around("@annotation(LogMethodCalls)")
  public Object logExecutionTime(ProceedingJoinPoint joinPoint) throws Throwable {

    String packageName = joinPoint.getSignature().getDeclaringType().getName();
    String methodName = joinPoint.getSignature().getName();
    Object[] argumentsList = joinPoint.getArgs();

    String arguments = listToString(argumentsList);

    log.info("enter {} - {}({})", packageName, methodName, arguments);

    Object proceed = joinPoint.proceed();

    log.info("exit {} - {}", packageName, methodName);

    return proceed;
  }
}
