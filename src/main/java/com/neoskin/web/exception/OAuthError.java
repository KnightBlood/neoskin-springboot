package com.neoskin.web.exception;

/** OAuth2 错误（对应 {@code League\OAuth2\Server\Exception\OAuthServerException} 的载荷）。 */
public final class OAuthError extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String error;
    private final String description;
    private final int status;

    public OAuthError(String error, String description, int status) {
        super(description);
        this.error = error;
        this.description = description;
        this.status = status;
    }

    public String error() {
        return error;
    }

    public String description() {
        return description;
    }

    public int status() {
        return status;
    }
}
