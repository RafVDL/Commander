package be.rafvdl.commander;

import be.rafvdl.commander.argument.*;
import be.rafvdl.commander.argument.Optional;
import com.flowpowered.math.vector.Vector3d;
import com.google.common.base.Preconditions;
import com.google.common.collect.ObjectArrays;
import org.spongepowered.api.CatalogType;
import org.spongepowered.api.Game;
import org.spongepowered.api.command.CommandException;
import org.spongepowered.api.command.CommandMessageFormatting;
import org.spongepowered.api.command.CommandResult;
import org.spongepowered.api.command.CommandSource;
import org.spongepowered.api.command.args.*;
import org.spongepowered.api.command.spec.CommandExecutor;
import org.spongepowered.api.command.spec.CommandSpec;
import org.spongepowered.api.entity.living.player.Player;
import org.spongepowered.api.text.Text;
import org.spongepowered.api.util.GuavaCollectors;
import org.spongepowered.api.util.StartsWithPredicate;
import org.spongepowered.api.world.Location;
import org.spongepowered.api.world.World;

import javax.annotation.Nullable;
import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.spongepowered.api.util.SpongeApiTranslationHelper.t;

/**
 * Commander class where all the magic happens.
 */
public class Commander {

    public static final byte METHODS = 0x01;
    public static final byte FIELDS = 0x02;
    public static final byte CLASSES = 0x04;

    private Object plugin;
    private Game game;

    private Map<String, Object> dependencies = new HashMap<String, Object>();

    public Commander(Object plugin, Game game) {
        this.plugin = checkNotNull(plugin);
        this.game = checkNotNull(game);

        if (getClass().getPackage().getName().equalsIgnoreCase("be.rafvdl.commander")) {
            System.err.println("COMMANDER IS RUNNING FROM THE DEFAULT PACKAGE.");
            System.err.println("IN ORDER TO BE COMPATIBLE WITH OTHER PLUGINS USING COMMANDER, YOU NEED TO CHANGE THE PACKAGE (shade)!");
        }
    }

    public void addDependency(String id, Object dependency) {
        dependencies.put(id, checkNotNull(dependency));
    }

    /**
     * Registers all available (child)commands in a given object.
     *
     * @param obj The object
     */
    public void register(Object obj) {
        register(obj, METHODS | FIELDS | CLASSES);
    }

    /**
     * Registers all available (child)commands in a given object.
     *
     * @param obj   The object
     * @param flags The flags
     */
    public void register(Object obj, int flags) {
        checkNotNull(obj);
        Collection<CommandTree> trees = registerClass(null, obj, obj.getClass(), flags);
        if (trees != null) {
            for (CommandTree tree : trees) {
                register(tree);
            }
        }
    }

    private Collection<CommandTree> registerClass(@Nullable CommandTree parent, Object obj, Class<?> clazz, int flags) {
        Collection<CommandTree> collection = new ArrayList<CommandTree>();
        if ((flags & CLASSES) == CLASSES) {
            if (clazz.isAnnotationPresent(Command.class)) {
                Command command = clazz.getAnnotation(Command.class);
                Command.Parent commandParent = clazz.getAnnotation(Command.Parent.class);
                Permission permission = clazz.getAnnotation(Permission.class);

                Method classMethod = null;

                if (commandParent != null) {
                    try {
                        Class<?>[] classes = ObjectArrays.concat(CommandSource.class, commandParent.value());
                        classMethod = clazz.getDeclaredMethod("parent", classes);
                    } catch (NoSuchMethodException e) {
                        System.err.println("Command.Parent annotation is available, but no method can be found.");
                        e.printStackTrace();
                    }
                }

                parent = new ClassCommandTree(null, obj, classMethod);
                if (classMethod != null) {
                    parent.arguments.addAll(checkArguments(game, parent, classMethod));
                }
                parent.aliases = command.value();
                if (parent.aliases.length == 0) {
                    parent.aliases = new String[]{clazz.getSimpleName().toLowerCase()};
                }
                if (permission != null) {
                    parent.setPermission(permission.value());
                }
                parent.description = command.description();
            }

            for (Class<?> nestedClazz : clazz.getDeclaredClasses()) {
                if (!Modifier.isStatic(nestedClazz.getModifiers())) {
                    System.err.println("Nested class " + nestedClazz.getSimpleName() + " is not static. This is a requirement.");
                    continue;
                }
                for (CommandTree tree : registerClass(parent, null, nestedClazz, flags)) {
                    if (parent == null) {
                        collection.add(tree);
                    } else {
                        parent.addChild(tree);
                    }
                }
            }

        }

        if ((flags & METHODS) == METHODS) {
            for (Method method : clazz.getDeclaredMethods()) {
                try {
                    CommandTree methodTree = createCommandTreeOfMethod(obj, method);

                    if (methodTree == null) {
                        continue;
                    }

                    if (parent == null) {
                        collection.add(methodTree);
                    } else {
                        parent.addChild(methodTree);
                    }
                } catch (IllegalAccessException e) {
                    e.printStackTrace();
                }
            }
        }

        if (parent != null) {
            collection.add(parent);
        }
        return collection;
    }

    private void register(CommandTree commandTree) {
        checkNotNull(commandTree);
        game.getCommandManager().register(plugin, commandTree.build(), commandTree.aliases);
    }

    private CommandTree createCommandTreeOfMethod(Object obj, Method method) throws IllegalAccessException {
        if (method.isAnnotationPresent(Ignore.class)) {
            return null;
        }
        Command command = method.getAnnotation(Command.class);
        if (command == null) {
            return null;
        }

        Permission permission = method.getAnnotation(Permission.class);

        CommandTree commandTree = checkMethod(game, null, obj, method);
        if (commandTree == null) {
            return null;
        }

        if (permission != null) {
            commandTree.setPermission(permission.value());
        }

        commandTree.aliases = command.value();
        if (commandTree.aliases.length == 0) {
            commandTree.aliases = new String[]{method.getName()};
        }
        commandTree.description = command.description();

        return commandTree;
    }

    private CommandTree checkMethod(Game game, CommandTree parent, Object obj, Method method) throws IllegalAccessException {
        Class<?>[] parameters = method.getParameterTypes();
        Preconditions.checkArgument(method.getReturnType().equals(CommandResult.class) && !(parameters.length > 0 && !parameters[0].equals(CommandSource.class)), !method.getReturnType().equals(CommandResult.class) ? "Method " + method.getName() + " must return CommandResult!" : "First argument of method " + method.getName() + " must be CommandSource!");

        CommandTree tree = new MethodCommandTree(parent, obj, method);
        tree.arguments.addAll(checkArguments(game, parent, method));

        return tree;
    }

    private List<Argument> checkArguments(Game game, CommandTree parent, Method method) {
        List<Argument> arguments = new ArrayList<Argument>();

        Class<?>[] parameters = method.getParameterTypes();
        for (int i = 1; i < parameters.length; i++) {
            Map<Class<? extends Annotation>, Annotation> annotations = new HashMap<Class<? extends Annotation>, Annotation>();
            for (Annotation annotation : method.getParameterAnnotations()[i]) {
                annotations.put(annotation.annotationType(), annotation);
            }
            arguments.add(checkParameter(game, parent, parameters[i], annotations, parameters[i].getSimpleName().toLowerCase() + i));
        }

        return arguments;
    }

    private Argument checkParameter(Game game, CommandTree parent, Class<?> parameter, Map<Class<? extends Annotation>, Annotation> annotations, String defaultKey) {
        Text key = Text.of(parameter.isAnnotationPresent(Key.class) ? parameter.getAnnotation(Key.class).value() : defaultKey);

        CommandElement commandElement = null;

        if (parameter.equals(String.class)) {
            if (annotations.containsKey(Flag.class) && parameter.isAnnotationPresent(Key.class)) {
                // TODO: long flag
            } else if (annotations.containsKey(Conjoin.class)) {
                commandElement = GenericArguments.remainingJoinedStrings(key);
            } else {
                commandElement = GenericArguments.string(key);
            }
        }
        if (parameter.equals(boolean.class)) {
            if (annotations.containsKey(Flag.class)) {
                if (parameter.isAnnotationPresent(Key.class)) {
                    // TODO: short flag
                }
            } else if (annotations.containsKey(Literal.class)) {
                Literal literal = getParameterAnnotation(annotations, Literal.class);
                commandElement = GenericArguments.literal(key, literal.value());
            } else {
                commandElement = GenericArguments.bool(key);
            }
        }

        if (parameter.equals(int.class)) {
            commandElement = GenericArguments.integer(key);
        }

        if (parameter.isEnum()) {
            commandElement = GenericArguments.enumValue(key, parameter.asSubclass(Enum.class));
        }

        if (parameter.isAssignableFrom(Player.class)) {
            commandElement = GenericArguments.player(key);
        }
        //TODO: PlayerOrSource
        if (parameter.isAssignableFrom(World.class)) {
            commandElement = GenericArguments.world(key);
        }

        if (parameter.equals(Location.class)) {
            commandElement = GenericArguments.location(key);
        }

        if (parameter.equals(Vector3d.class)) {
            commandElement = GenericArguments.vector3d(key);
        }

        if (CatalogType.class.isAssignableFrom(parameter)) {
            commandElement = GenericArguments.catalogedElement(key, parameter.asSubclass(CatalogType.class));
        }

        if (annotations.containsKey(Choices.class)) {
            Choices choices = getParameterAnnotation(annotations, Choices.class);
            if (choices.current()) {
                commandElement = new CurrentChoicesCommandElement(key, (Map<String, ?>) dependencies.get(choices.value()), false);
            } else {
                GenericArguments.choices(key, (Map<String, ?>) dependencies.get(choices.value()), false);
            }
        }

        if (commandElement != null && annotations.containsKey(Optional.class)) {
            if (getParameterAnnotation(annotations, Optional.class).weak()) {
                commandElement = GenericArguments.optionalWeak(commandElement);
            } else {
                commandElement = GenericArguments.optional(commandElement);
            }
        }

        if (commandElement != null && annotations.containsKey(Permission.class)) {
            Permission permission = getParameterAnnotation(annotations, Permission.class);
            if (!permission.value().isEmpty()) {
                commandElement = GenericArguments.requiringPermission(commandElement, (parent != null ? parent.permission() + "." : "") + permission
                        .value());
            }
        }

        return new Argument(key.toPlain(), commandElement);
    }

    private <T extends Annotation> T getParameterAnnotation(Map<Class<? extends Annotation>, Annotation> annotations, Class<T> annotation) {
        return annotation.cast(annotations.get(annotation));
    }

    private static class Argument {

        private String key;
        private boolean collection = false;
        private CommandElement commandElement;

        Argument(String key, CommandElement commandElement) {
            this.key = key;
            this.commandElement = commandElement;
        }

    }

    private static abstract class CommandTree {

        private CommandTree parent;
        protected List<Argument> arguments = new ArrayList<Argument>();
        private Set<CommandTree> children = new HashSet<CommandTree>();

        String[] aliases;
        private String permission = "";
        String description = "";

        CommandTree(CommandTree parent) {
            this.parent = parent;
        }

        void addChild(CommandTree tree) {
            children.add(tree);
        }

        Set<CommandTree> children() {
            return children;
        }

        void setPermission(String permission) {
            this.permission = permission;
        }

        String permission() {
            return (parent != null ? parent.permission() + "." : "") + (permission.isEmpty() ? aliases[0] : permission);
        }

        abstract CommandExecutor executor();

        abstract CommandSpec build();

    }

    private static class MethodCommandTree extends CommandTree {

        protected Object obj;
        protected Method method;

        MethodCommandTree(CommandTree parent, Object obj, Method method) {
            super(parent);
            this.obj = obj;
            this.method = method;
        }

        @Override
        CommandExecutor executor() {
            return new CommandExecutor() {
                @Override
                public CommandResult execute(CommandSource src, CommandContext args) throws CommandException {
                    try {
                        List<Object> arguments = new ArrayList<Object>();
                        arguments.add(src);
                        for (Argument argument : MethodCommandTree.this.arguments) {
                            if (argument.collection) {
                                arguments.add(args.getAll(argument.key));
                            } else {
                                arguments.add(args.getOne(argument.key).orElse(null));
                            }
                        }
                        return (CommandResult) method.invoke(obj, arguments.toArray());
                    } catch (IllegalAccessException e) {
                        e.printStackTrace();
                    } catch (InvocationTargetException e) {
                        e.printStackTrace();
                    }
                    return CommandResult.empty();
                }
            };
        }

        @Override
        CommandSpec build() {
            CommandSpec.Builder builder = CommandSpec.builder();
            builder.executor(executor());
            if (!permission().isEmpty()) {
                builder.permission(permission());
            }
            CommandElement[] commandElements = new CommandElement[arguments.size()];
            for (int i = 0; i < arguments.size(); i++) {
                commandElements[i] = arguments.get(i).commandElement;
            }
            builder.arguments(commandElements);
            return builder.build();
        }

    }

    private static class ClassCommandTree extends MethodCommandTree {

        ClassCommandTree(CommandTree parent, Object obj, Method method) {
            super(parent, obj, method);
        }

        @Override
        CommandSpec build() {
            CommandSpec.Builder builder = CommandSpec.builder();
            if (!permission().isEmpty()) {
                builder.permission(permission());
            }
            if (method != null) {
                builder.executor(executor());
                CommandElement[] commandElements = new CommandElement[arguments.size()];
                for (int i = 0; i < arguments.size(); i++) {
                    commandElements[i] = arguments.get(i).commandElement;
                }
                builder.arguments(commandElements);
            }
            for (CommandTree tree : children()) {
                builder.child(tree.build(), tree.aliases);
            }
            return builder.build();
        }
    }

    private static class CurrentChoicesCommandElement extends CommandElement {
        private final Map<String, ?> choices;
        private final boolean choicesInUsage;

        private CurrentChoicesCommandElement(@Nullable Text key, Map<String, ?> choices, boolean choicesInUsage) {
            super(key);
            this.choices = choices;
            this.choicesInUsage = choicesInUsage;
        }

        @Override
        protected Object parseValue(CommandSource source, CommandArgs args) throws ArgumentParseException {
            Object value = this.choices.get(args.next());
            if (value == null) {
                throw args.createError(t("Argument was not a valid choice. Valid choices: %s", this.choices.keySet().toString()));
            }
            return value;
        }

        @Override
        public List<String> complete(CommandSource src, CommandArgs args, CommandContext context) {
            final String prefix = args.nextIfPresent().orElse("");
            return this.choices.keySet().stream().filter(new StartsWithPredicate(prefix)).collect(GuavaCollectors.<String>toImmutableList());
        }

        @Override
        public Text getUsage(CommandSource commander) {
            if (this.choicesInUsage) {
                final Text.Builder build = Text.builder();
                build.append(CommandMessageFormatting.LT_TEXT);
                for (Iterator<String> it = this.choices.keySet().iterator(); it.hasNext(); ) {
                    build.append(Text.of(it.next()));
                    if (it.hasNext()) {
                        build.append(CommandMessageFormatting.PIPE_TEXT);
                    }
                }
                build.append(CommandMessageFormatting.GT_TEXT);
                return build.build();
            } else {
                return super.getUsage(commander);
            }
        }
    }

}
