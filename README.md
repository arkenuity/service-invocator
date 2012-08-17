Service Invocator
==================

A service invocation executor utility, which provides the following:
   o Instrument specific aspects (time duration, success/failure times) etc as dictated by the caller.
   o Enforce conformance (maxWaitTime, maxRetries etc) an aspects provided by the client.

      UserProfile profile = ServiceInvocation.execute(new Callable<UserProfile>() {
          &#064Conform(retryCount=2, maxTimeWait=100, maxWaitTimeUnit=TimeUnit.MILLISECONDS)
          &#064Instrumented(clazz=UserProfileServiceProxy.class, method="byProfileId")
          public UserProfile call() {
              profileService.byProfile(profileId);
          }});
  
Note: This utility uses a  {@link ExecutorService#newCachedThreadPool()} and process the tasks submitted through the Callable using the threads in the pool.
