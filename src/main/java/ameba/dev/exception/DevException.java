package ameba.dev.exception;

import ameba.exception.AmebaException;

/**
 * @author icode
 */
public class DevException extends AmebaException {
    public DevException() {
    }

    public DevException(Throwable cause) {
        super(cause);
    }

    public DevException(String message) {
        super(message);
    }

    public DevException(String message, Throwable cause) {
        super(message, cause);
    }
}
