package com.github.arkenuity.service.essentials.misc;

/**
 *
 * @author <a href="mailto:arkenuity@gmail.com">Rajesh Kumar Arcot</a>
 *
 */
@SuppressWarnings("serial")
public class ServiceInvocationException extends RuntimeException {

    public ServiceInvocationException() {
        super();
    }

    public ServiceInvocationException(final String message, final Throwable cause) {
        super(message, cause);
    }

    public ServiceInvocationException(final Throwable cause) {
        super(cause);
    }

}