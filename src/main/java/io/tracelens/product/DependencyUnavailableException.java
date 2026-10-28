package io.tracelens.product;

public class DependencyUnavailableException extends RuntimeException {
    private final String dependency;

    public DependencyUnavailableException(String dependency, Throwable cause) {
        super("A required dependency is unavailable", cause);
        this.dependency = dependency;
    }

    public String dependency() {
        return dependency;
    }
}
