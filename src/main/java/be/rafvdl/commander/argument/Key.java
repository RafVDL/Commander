package be.rafvdl.commander.argument;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Sets the key of the argument.
 * <p>This is used by:
 * <ul>
 * <li>the usage of the command</li>
 * <li>{@link Flag}s</li>
 * </ul></p>
 * <p>If the parameter has no key, the key will be the index of the parameter.</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.PARAMETER})
public @interface Key {

    String value();

}
