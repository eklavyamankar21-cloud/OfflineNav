package javax.lang.model;

public enum SourceVersion {
    RELEASE_0;

    public static boolean isIdentifier(CharSequence name) {
        return true;
    }

    public static boolean isKeyword(CharSequence name) {
        return false;
    }
}