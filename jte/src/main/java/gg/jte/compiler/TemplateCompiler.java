package gg.jte.compiler;

import gg.jte.*;
import gg.jte.compiler.java.ClassFilesCompiler;
import gg.jte.compiler.java.JavaCodeGenerator;
import gg.jte.runtime.*;
import gg.jte.output.FileOutput;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class TemplateCompiler extends TemplateLoader {

    public static final boolean DEBUG = false;

    private final TemplateConfig config;
    private final CodeResolver codeResolver;
    private final ClassLoader parentClassLoader;

    private final ConcurrentHashMap<String, LinkedHashSet<String>> templateDependencies = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<ParamInfo>> paramOrder = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ClassInfo> templateByClassName = new ConcurrentHashMap<>();

    public TemplateCompiler(TemplateConfig config, CodeResolver codeResolver, Path classDirectory, ClassLoader parentClassLoader) {
        super(classDirectory);
        this.config = config;
        this.codeResolver = codeResolver;
        this.parentClassLoader = parentClassLoader;
    }

    @Override
    public Template load(String name) {
        precompile(Collections.singletonList(name), null);
        return super.load(name);
    }

    @Override
    protected ClassInfo getClassInfo(ClassLoader classLoader, String className) {
        return templateByClassName.get(className);
    }

    @Override
    protected ClassLoader getClassLoader() {
        return createClassLoader(parentClassLoader);
    }

    public void cleanAll() {
        IoUtils.deleteDirectoryContent(classDirectory.resolve(Constants.PACKAGE_NAME.replace('.', '/')));
    }

    @Override
    public List<String> generateAll() {
        LinkedHashSet<ClassDefinition> classDefinitions = generate(codeResolver.resolveAllTemplateNames());
        return classDefinitions.stream().map(ClassDefinition::getJavaFileName).collect(Collectors.toList());
    }

    public List<String> precompileAll(List<String> compilePath) {
        return precompile(codeResolver.resolveAllTemplateNames(), compilePath);
    }

    public List<String> precompile(List<String> names, List<String> compilePath) {
        LinkedHashSet<ClassDefinition> classDefinitions = generate(names);

        String[] files = new String[classDefinitions.size()];
        int i = 0;
        for (ClassDefinition classDefinition : classDefinitions) {
            files[i++] = classDirectory.resolve(classDefinition.getJavaFileName()).toFile().getAbsolutePath();
        }

        ClassFilesCompiler.compile(files, compilePath, config.compileArgs, classDirectory, templateByClassName);

        return classDefinitions.stream().map(ClassDefinition::getJavaFileName).collect(Collectors.toList());
    }

    private LinkedHashSet<ClassDefinition> generate(List<String> names) {
        LinkedHashSet<ClassDefinition> classDefinitions = new LinkedHashSet<>();
        for (String name : names) {
            switch (getTemplateType(name)) {
                case Template:
                    generateTemplate(name, classDefinitions);
                    break;
                case Tag:
                    generateTemplateFromTag(name, classDefinitions);
                    break;
                case Layout:
                    generateTemplateFromLayout(name, classDefinitions);
                    break;
            }
        }

        for (ClassDefinition classDefinition : classDefinitions) {
            try (FileOutput fileOutput = new FileOutput(classDirectory.resolve(classDefinition.getJavaFileName()))) {
                fileOutput.writeContent(classDefinition.getCode());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }

            List<byte[]> textParts = classDefinition.getBinaryTextParts();
            if (!textParts.isEmpty()) {
                try (OutputStream os = Files.newOutputStream(classDirectory.resolve(classDefinition.getBinaryTextPartsFileName()), StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.CREATE)) {
                    for (byte[] textPart : textParts) {
                        os.write(textPart);
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        }
        return classDefinitions;
    }

    private void generateTemplate(String name, LinkedHashSet<ClassDefinition> classDefinitions) {
        String code = resolveCode(name, null);

        LinkedHashSet<String> templateDependencies = new LinkedHashSet<>();

        ClassInfo templateInfo = new ClassInfo(name, Constants.PACKAGE_NAME);

        JavaCodeGenerator codeGenerator = new JavaCodeGenerator(this, this.config, paramOrder, templateInfo, classDefinitions, templateDependencies);
        new TemplateParser(code, TemplateType.Template, codeGenerator, config).parse();

        this.templateDependencies.put(name, templateDependencies);

        ClassDefinition templateDefinition = new ClassDefinition(templateInfo.fullName);
        templateDefinition.setCode(codeGenerator.getCode(), codeGenerator.getBinaryTextParts());
        classDefinitions.add(templateDefinition);

        templateByClassName.put(templateDefinition.getName(), templateInfo);

        if (DEBUG) {
            System.out.println(templateDefinition.getCode());
        }
    }

    private void generateTemplateFromTag(String name, LinkedHashSet<ClassDefinition> classDefinitions) {
        LinkedHashSet<String> templateDependencies = new LinkedHashSet<>();

        ClassInfo templateInfo = generateTagOrLayout(TemplateType.Tag, name, classDefinitions, templateDependencies, null);

        this.templateDependencies.put(name, templateDependencies);

        templateByClassName.put(templateInfo.name, templateInfo);
    }

    private void generateTemplateFromLayout(String name, LinkedHashSet<ClassDefinition> classDefinitions) {
        LinkedHashSet<String> templateDependencies = new LinkedHashSet<>();

        ClassInfo templateInfo = generateTagOrLayout(TemplateType.Layout, name, classDefinitions, templateDependencies, null);

        this.templateDependencies.put(name, templateDependencies);

        templateByClassName.put(templateInfo.name, templateInfo);
    }

    public ClassInfo generateTagOrLayout(TemplateType type, String name, LinkedHashSet<ClassDefinition> classDefinitions, LinkedHashSet<String> templateDependencies, DebugInfo debugInfo) {
        templateDependencies.add(name);
        ClassInfo classInfo = new ClassInfo(name, Constants.PACKAGE_NAME);

        ClassDefinition classDefinition = new ClassDefinition(classInfo.fullName);
        if (classDefinitions.contains(classDefinition)) {
            return classInfo;
        }

        String code = resolveCode(name, debugInfo);

        classDefinitions.add(classDefinition);

        JavaCodeGenerator codeGenerator = new JavaCodeGenerator(this, this.config, paramOrder, classInfo, classDefinitions, templateDependencies);
        new TemplateParser(code, type, codeGenerator, config).parse();

        classDefinition.setCode(codeGenerator.getCode(), codeGenerator.getBinaryTextParts());
        templateByClassName.put(classDefinition.getName(), classInfo);

        if (DEBUG) {
            System.out.println(classDefinition.getCode());
        }

        return classInfo;
    }

    private String resolveCode(String name, DebugInfo debugInfo) {
        String code = codeResolver.resolve(name);
        if (code == null) {
            String message = name + " not found";
            if (debugInfo != null) {
                message += ", referenced at " + debugInfo.name + ":" + debugInfo.line;
            }
            throw new TemplateNotFoundException(message);
        }
        return code;
    }

    @Override
    public boolean hasChanged(String name) {
        if (codeResolver.hasChanged(name)) {
            return true;
        }

        LinkedHashSet<String> dependencies = templateDependencies.get(name);
        if (dependencies == null) {
            return false;
        }

        for (String dependency : dependencies) {
            if (codeResolver.hasChanged(dependency)) {
                return true;
            }
        }

        return false;
    }

    @Override
    public List<String> getTemplatesUsing(String name) {
        List<String> result = new ArrayList<>();
        for (Map.Entry<String, LinkedHashSet<String>> dependencies : templateDependencies.entrySet()) {
            if (dependencies.getValue().contains(name)) {
                result.add(dependencies.getKey());
            }
        }

        return result;
    }
}
