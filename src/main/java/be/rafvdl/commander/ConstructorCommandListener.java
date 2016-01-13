package be.rafvdl.commander;

public interface ConstructorCommandListener<E> {

    void call(E instance);
}
