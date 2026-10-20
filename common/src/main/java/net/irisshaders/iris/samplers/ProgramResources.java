package net.irisshaders.iris.samplers;

import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import it.unimi.dsi.fastutil.objects.Object2LongLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import net.irisshaders.iris.backend.IrisImage;
import net.irisshaders.iris.backend.RenderGraph;
import net.irisshaders.iris.gl.buffer.ShaderStorageBufferHolder;
import net.irisshaders.iris.gl.image.ImageHolder;
import net.irisshaders.iris.gl.sampler.GlSampler;
import net.irisshaders.iris.gl.sampler.SamplerHolder;
import net.irisshaders.iris.gl.state.ValueUpdateNotifier;
import net.irisshaders.iris.gl.texture.InternalTextureFormat;
import net.irisshaders.iris.gl.texture.TextureType;
import net.irisshaders.iris.pipeline.GraphicsBackend;
import net.irisshaders.iris.pipeline.transform.ShaderResourceLayout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

public class ProgramResources implements SamplerHolder, ImageHolder {
	private final PackSamplers pack;
	private final List<ShaderResourceLayout.Descriptor> storageDeclarations;
	private final List<ShaderResourceLayout.Descriptor> uniformDeclarations;
	private final Map<PackSamplers.Reference, LongSupplier> texels = new LinkedHashMap<>();
	private final Map<String, ShaderResourceLayout.Descriptor> declarations = new LinkedHashMap<>();
	private final Map<String, PackSamplers.Reference> references = new LinkedHashMap<>();
	private final Map<PackSamplers.Reference, TextureBinding> textures = new LinkedHashMap<>();
	private final Map<PackSamplers.Reference, GlTextureBinding> glTextures = new LinkedHashMap<>();
	private final Map<String, ShaderResourceLayout.Descriptor> imageDeclarations = new LinkedHashMap<>();
	private final Map<String, InternalTextureFormat> imageFormats = new LinkedHashMap<>();
	private final Map<String, Supplier<ImageBinding>> images = new LinkedHashMap<>();
	private ShaderStorageBufferHolder storageBuffers;
	private Supplier<Map<String, List<GpuBufferSlice>>> uniformBuffers = Map::of;
	private TextureBinding fallback;
	private boolean drawFallback;
	private IntFunction<TextureBinding> externalBindings;
	private SamplerBindingPlan plan;

	public ProgramResources(PackSamplers pack, ShaderResourceLayout layout) {
		this.pack = pack;
		this.storageDeclarations = layout.descriptors().stream().filter(d -> d.kind() == ShaderResourceLayout.Kind.STORAGE_BUFFER).toList();
		this.uniformDeclarations = layout.descriptors().stream().filter(d -> d.kind() == ShaderResourceLayout.Kind.UNIFORM_BUFFER).toList();

		for (var descriptor : layout.descriptors()) {
			if (descriptor.kind() == ShaderResourceLayout.Kind.IMAGE) {
				this.imageDeclarations.put(descriptor.name(), descriptor);
			} else if (descriptor.kind() == ShaderResourceLayout.Kind.SAMPLER) {
				this.declarations.put(descriptor.name(), descriptor);
			}
		}
	}

	public void addStorageBuffers(ShaderStorageBufferHolder buffers) {
		this.storageBuffers = buffers;
		this.getBufferBindings();
	}

	public void setUniformBuffers(Supplier<Map<String, List<GpuBufferSlice>>> buffers) {
		this.uniformBuffers = buffers;
	}

	public net.irisshaders.iris.gl.buffer.ShaderStorageBuffer getStorageBuffer(int index) {
		return this.storageBuffers.getBuffer(index);
	}

	public Map<String, List<GpuBufferSlice>> getBufferBindings() {
		var result = new LinkedHashMap<String, List<GpuBufferSlice>>();

		for (var descriptor : this.storageDeclarations) {
			if (this.storageBuffers == null) {
				throw new IllegalArgumentException("no buffer objects for storage block " + descriptor.name());
			}

			int first = descriptor.originalBinding() == null ? 0 : descriptor.originalBinding();
			var slices = new ArrayList<GpuBufferSlice>();

			for (int i = 0; i < descriptor.count(); i++) {
				slices.add(this.storageBuffers.getBuffer(Math.addExact(first, i)).slice());
			}

			result.put(descriptor.name(), List.copyOf(slices));
		}

		result.putAll(this.uniformBuffers.get());

		return result;
	}

	/** Resolve resource identities only while compiling the pack graph, on either backend. */
	public List<RenderGraph.Access> graphAccesses() {
		var accesses = new ArrayList<RenderGraph.Access>();
		for (var entry : this.references.entrySet()) {
			var descriptor = this.declarations.get(entry.getKey());
			var binding = this.textures.get(entry.getValue());
			RenderGraph.Resource resource;
			if (binding == null) {
				// Legacy GL samplers and per-draw texel views have no stable parent resource at pack load.
				resource = new RenderGraph.Resource(!descriptor.type().endsWith("BUFFER"), 0);
			} else {
				resource = binding.image() == null ? RenderGraph.image(binding.texture().get().texture())
					: new RenderGraph.Resource(true, binding.image().get().getImage());
			}
			accesses.add(new RenderGraph.Access(resource, RenderGraph.stages(descriptor.stages()), RenderGraph.Usage.SHADER.bit, 0));
		}
		var images = this.getImages();
		for (var descriptor : this.imageDeclarations.values()) {
			for (var binding : images.get(descriptor.name())) {
				var resource = binding.image() == null ? RenderGraph.image(binding.texture().texture())
					: new RenderGraph.Resource(true, binding.image().getImage());
				accesses.add(new RenderGraph.Access(resource, RenderGraph.stages(descriptor.stages()),
					descriptor.access().reads() ? RenderGraph.Usage.SHADER.bit : 0,
					descriptor.access().writes() ? RenderGraph.Usage.SHADER.bit : 0));
			}
		}
		var buffers = this.getBufferBindings();
		for (var descriptor : this.uniformDeclarations) {
			for (var slice : buffers.getOrDefault(descriptor.name(), List.of())) {
				accesses.add(new RenderGraph.Access(RenderGraph.buffer(slice.buffer()), RenderGraph.stages(descriptor.stages()), RenderGraph.Usage.UNIFORM.bit, 0));
			}
		}
		for (var descriptor : this.storageDeclarations) {
			for (var slice : buffers.get(descriptor.name())) {
				accesses.add(new RenderGraph.Access(RenderGraph.buffer(slice.buffer()), RenderGraph.stages(descriptor.stages()),
					descriptor.access().reads() ? RenderGraph.Usage.SHADER.bit : 0,
					descriptor.access().writes() ? RenderGraph.Usage.SHADER.bit : 0));
			}
		}
		return accesses;
	}

	public Bindings resolve() {
		return this.resolve(Map.of());
	}

	public Bindings resolve(Map<String, List<GpuBufferSlice>> supplied) {
		var resolvedTextures = new LinkedHashMap<PackSamplers.Reference, TextureBinding.Resolved>();
		this.textures.forEach((reference, binding) -> resolvedTextures.put(reference, binding.resolve(reference.type())));
		var resolvedTexels = new Object2LongLinkedOpenHashMap<PackSamplers.Reference>();
		this.texels.forEach((reference, view) -> resolvedTexels.put(reference, view.getAsLong()));
		var buffers = this.getBufferBindings();
		buffers.putAll(supplied);
		return new Bindings(resolvedTextures, resolvedTexels, buffers, this.getImages());
	}

	@Override
	public boolean hasSampler(String name) {
		return this.declarations.containsKey(name);
	}

	@Override
	public boolean addTextureSampler(TextureBinding binding, boolean defaultSampler, String... names) {
		return this.addTextureSampler(binding, defaultSampler, false, names);
	}

	@Override
	public boolean addDrawTextureSampler(TextureBinding binding, boolean defaultSampler, String... names) {
		return this.addTextureSampler(binding, defaultSampler, true, names);
	}

	private boolean addTextureSampler(TextureBinding binding, boolean defaultSampler, boolean draw, String... names) {
		if (defaultSampler) {
			this.fallback = binding;
			this.drawFallback = draw;
		}

		boolean used = false;

		for (String name : names) {
			var declaration = this.declarations.get(name);

			if (declaration == null) {
				continue;
			}

			if (declaration.count() != 1) {
				throw new UnsupportedOperationException("sampler array registration is not implemented: " + name);
			}

			var type = SamplerHeapLayout.Type.valueOf(declaration.type());

			if (type.texelBuffer()) {
				throw new UnsupportedOperationException("texel samplers require a buffer view: " + name);
			}

			var reference = this.pack.register(type, binding.key(), draw);
			this.references.put(name, reference);
			this.textures.put(reference, binding);
			used = true;
		}

		return used;
	}

	public void addTexelSampler(String name, Object key, LongSupplier view) {
		var declaration = this.declarations.get(name);

		if (declaration == null) {
			return;
		}

		var type = SamplerHeapLayout.Type.valueOf(declaration.type());

		if (!type.texelBuffer() || declaration.count() != 1) {
			throw new IllegalArgumentException("expected scalar texel sampler: " + name);
		}

		var reference = this.pack.register(type, key, true);
		this.references.put(name, reference);
		this.texels.put(reference, view);
	}

	public void finish() {
		this.getBufferBindings();

		if (!this.images.keySet().equals(this.imageDeclarations.keySet())) {
			throw new IllegalArgumentException("missing storage image bindings: " + this.imageDeclarations.keySet());
		}

		for (String name : this.declarations.keySet()) {
			if (!this.references.containsKey(name)) {
				if (this.fallback == null || !this.declarations.get(name).type().equals("SAMPLER2D")) {
					throw new IllegalArgumentException("no sampler registered for " + name);
				}

				this.addTextureSampler(this.fallback, false, this.drawFallback, name);
			}
		}

		var referenced = new HashSet<>(this.references.values());
		this.textures.keySet().retainAll(referenced);
		this.glTextures.keySet().retainAll(referenced);
		this.pack.getResources().register(this);
	}

	public PackSamplers getPackSamplers() {
		return this.pack;
	}

	public Map<String, PackSamplers.Reference> getSamplerReferences() {
		return Collections.unmodifiableMap(this.references);
	}

	public Map<PackSamplers.Reference, TextureBinding> getTextureBindings() {
		return Collections.unmodifiableMap(this.textures);
	}

	public GlTextureBinding getGlTextureBinding(PackSamplers.Reference reference) {
		return this.glTextures.get(reference);
	}

	public Map<PackSamplers.Reference, LongSupplier> getTexelBufferBindings() {
		return Collections.unmodifiableMap(this.texels);
	}

	public Map<String, ShaderResourceLayout.Descriptor> getSamplerDeclarations() {
		return Collections.unmodifiableMap(this.declarations);
	}

	public Map<String, ShaderResourceLayout.Descriptor> getImageDeclarations() {
		return Collections.unmodifiableMap(this.imageDeclarations);
	}

	public Map<String, InternalTextureFormat> getImageFormats() {
		return Collections.unmodifiableMap(this.imageFormats);
	}

	private Map<String, List<ImageBinding>> getImages() {
		var result = new LinkedHashMap<String, List<ImageBinding>>();
		this.images.forEach((name, image) -> result.put(name, List.of(image.get())));

		return result;
	}

	public List<ShaderResourceLayout.Descriptor> getUniformDeclarations() {
		return this.uniformDeclarations;
	}

	public List<ShaderResourceLayout.Descriptor> getStorageDeclarations() {
		return this.storageDeclarations;
	}

	public SamplerBindingPlan getBindingPlan() {
		if (this.plan == null) {
			var builder = new SamplerBindingPlan.Builder(this.pack.getLayout());
			this.references.forEach((name, reference) -> builder.bind(name, this.pack.getSlot(reference)));
			this.plan = builder.build();
		}

		return this.plan;
	}

	private boolean unsupported(String... names) {
		for (String name : names) {
			if (this.hasSampler(name)) {
				throw new UnsupportedOperationException("sampler still uses a GL texture name: " + name);
			}
		}

		return false;
	}

	public void setExternalBindings(IntFunction<TextureBinding> bindings) {
		this.externalBindings = bindings;
	}

	@Override
	public void addExternalSampler(int unit, String... names) {
		if (this.externalBindings == null) {
			this.unsupported(names);
		} else {
			this.addDrawTextureSampler(this.externalBindings.apply(unit), unit == 0, names);
		}
	}

	@Override
	public boolean hasImage(String name) {
		return this.imageDeclarations.containsKey(name);
	}

	@Override
	public void addTextureImage(Supplier<GpuTextureView> texture, InternalTextureFormat format, String name) {
		this.addImageBinding(() -> new ImageBinding(texture.get(), null), format, name);
	}

	@Override
	public void addImage(Supplier<IrisImage> image, InternalTextureFormat format, String name) {
		this.addImageBinding(() -> new ImageBinding(null, image.get()), format, name);
	}

	private void addImageBinding(Supplier<ImageBinding> image, InternalTextureFormat format, String name) {
		if (!this.hasImage(name)) {
			return;
		}

		if (this.imageDeclarations.get(name).count() != 1) {
			throw new UnsupportedOperationException("image arrays require element bindings: " + name);
		}

		this.images.put(name, image);
		this.imageFormats.put(name, format);
	}

	@Override
	public boolean addDefaultSampler(TextureType type, IntSupplier texture, ValueUpdateNotifier notifier, Supplier<GlSampler> sampler,
		String... names) {
		return this.unsupported(names);
	}

	@Override
	public boolean addDynamicSampler(TextureType type, IntSupplier texture, Supplier<GlSampler> sampler, String... names) {
		return this.addDynamicSampler(type, texture, null, sampler, names);
	}

	@Override
	public boolean addDynamicSampler(TextureType type, IntSupplier texture, ValueUpdateNotifier notifier, Supplier<GlSampler> sampler,
		String... names) {
		if (!GraphicsBackend.isOpenGL()) {
			return this.unsupported(names);
		}

		var binding = new GlTextureBinding(type, texture, sampler, notifier);
		boolean used = false;
		for (String name : names) {
			var declaration = this.declarations.get(name);
			if (declaration == null) {
				continue;
			}
			if (declaration.count() != 1) {
				throw new UnsupportedOperationException("sampler array registration is not implemented: " + name);
			}
			var samplerType = SamplerHeapLayout.Type.valueOf(declaration.type());
			if (samplerType.texelBuffer()) {
				throw new UnsupportedOperationException("texel samplers require a buffer view: " + name);
			}
			var reference = this.pack.register(samplerType, binding, true);
			this.references.put(name, reference);
			this.glTextures.put(reference, binding);
			used = true;
		}
		return used;
	}

	public record GlTextureBinding(TextureType type, IntSupplier texture, Supplier<GlSampler> sampler, ValueUpdateNotifier notifier) {
	}

	public record Bindings(Map<PackSamplers.Reference, TextureBinding.Resolved> textures, Object2LongMap<PackSamplers.Reference> texels, Map<String, List<GpuBufferSlice>> buffers, Map<String, List<ImageBinding>> images) {
	}

	public record ImageBinding(GpuTextureView texture, IrisImage image) {
	}
}
