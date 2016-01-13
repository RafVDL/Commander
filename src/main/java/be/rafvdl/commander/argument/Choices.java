package be.rafvdl.commander.argument;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.PARAMETER})
public @interface Choices {

    /**
     * The ID of the Map passed to the Commander instance.
     *
     * @return the ID
     */
    String value();

    boolean current() default false;

}
