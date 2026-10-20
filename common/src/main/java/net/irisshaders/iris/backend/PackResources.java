package net.irisshaders.iris.backend;

import net.irisshaders.iris.samplers.ProgramResources;

public interface PackResources extends AutoCloseable {
	public void beginFrame();

	public default void register(ProgramResources resources) {
	}

	@Override
	public void close();
}
