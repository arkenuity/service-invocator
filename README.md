Service Invocator
==================

A service invocation utility, which provides a nifty way to wrap external service calls and provide the following:
   * Instrumentation on specific aspects (time duration, success/failure counts) etc as dictated by the caller.
   * Enforce conformance on aspects such as maxWaitTime (timeout), retries etc

Example callout:

      UserProfile profile = ServiceInvocation.execute(new Callable<UserProfile>() {
          &#064Conform(retryCount=2, maxTimeWait=100, maxWaitTimeUnit=TimeUnit.MILLISECONDS)
          &#064Instrumented(clazz=UserProfileServiceProxy.class, method="byProfileId")
          public UserProfile call() {
              profileService.byProfile(profileId);
          }});
  
Note: This utility uses [Executors.newCachedThreadPool()] http://docs.oracle.com/javase/6/docs/api/java/util/concurrent/Executors.html#newCachedThreadPool(java.util.concurrent.ThreadFactory) and process the tasks submitted through the Callable using the threads in the pool.
