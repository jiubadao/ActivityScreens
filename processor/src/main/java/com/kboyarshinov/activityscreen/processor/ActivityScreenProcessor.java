package com.kboyarshinov.activityscreen.processor;

import com.google.auto.service.AutoService;
import com.kboyarshinov.activityscreens.annotation.ActivityArg;
import com.kboyarshinov.activityscreens.annotation.ActivityScreen;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Annotation Processor for @ActivityScreen and @ActivityArg annotations
 *
 * @author Kirill Boyarshinov
 */
@AutoService(Processor.class)
public class ActivityScreenProcessor extends AbstractProcessor {
    private Types typeUtils;
    private Elements elementUtils;
    private Filer filer;
    private Messager messager;

    private HashMap<Name, ActivityScreenAnnotatedClass> activityClasses = new HashMap<Name, ActivityScreenAnnotatedClass>();

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        typeUtils = processingEnv.getTypeUtils();
        elementUtils = processingEnv.getElementUtils();
        filer = processingEnv.getFiler();
        messager = processingEnv.getMessager();
    }

    @Override
    public Set<String> getSupportedAnnotationTypes() {
        Set<String> annotations = new LinkedHashSet<String>();
        annotations.add(ActivityScreen.class.getCanonicalName());
        annotations.add(ActivityArg.class.getCanonicalName());
        return annotations;
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    /**
     * Checks if the annotated element observes our rules
     */
    private boolean isValidActivityClass(TypeElement classElement) {

        // Check if it's public
        if (!classElement.getModifiers().contains(Modifier.PUBLIC)) {
            error(classElement, "The class %s is not public.", classElement.getQualifiedName().toString());
            return false;
        }

        // Check if it's an abstract class
        if (classElement.getModifiers().contains(Modifier.ABSTRACT)) {
            error(classElement, "The class %s is abstract. You can't annotate abstract classes with @%s.",
                    classElement.getQualifiedName().toString(), ActivityScreen.class.getSimpleName());
            return false;
        }

        // Check if it's an interface
        if (classElement.getKind().isInterface()) {
            error(classElement, "%s is interface. You can't annotate interfaces with @%s.",
                    classElement.getQualifiedName().toString(), ActivityScreen.class.getSimpleName());
            return false;
        }

        // Check Activity inheritance
        if (!isInheritsActivity(classElement)) {
            error(classElement, "%s can only be used on activities, but %s is not a subclass of activity.",
                    ActivityScreen.class.getSimpleName(), classElement.getQualifiedName());
            return false;
        }
        return true;
    }

    private boolean isAnnotatedActivity(TypeElement classElement) {
        // Check Activity inheritance
        if (!isInheritsActivity(classElement)) {
            return false;
        }
        // Check Activity has @ActivityScreen annotation
        ActivityScreen annotation = classElement.getAnnotation(ActivityScreen.class);
        return annotation != null;
    }

    private boolean isInheritsActivity(TypeElement classElement) {
        TypeElement activityType = elementUtils.getTypeElement("android.app.Activity");
        return activityType != null && typeUtils.isSubtype(classElement.asType(), activityType.asType());
    }

    private boolean isValidField(Element annotatedElement) {
        return !annotatedElement.getModifiers().contains(Modifier.FINAL)
                || !annotatedElement.getModifiers().contains(Modifier.STATIC)
                || !annotatedElement.getModifiers().contains(Modifier.PRIVATE)
                || !annotatedElement.getModifiers().contains(Modifier.PROTECTED);
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        for (Element annotatedElement : roundEnv.getElementsAnnotatedWith(ActivityArg.class)) {
            if (annotatedElement.getKind() != ElementKind.FIELD) {
                error(annotatedElement, "Only field can be annotated with @%s", ActivityArg.class.getSimpleName());
                return true;
            }

            TypeElement enclosingElement = (TypeElement) annotatedElement.getEnclosingElement();
            Name activityName = enclosingElement.getQualifiedName();

            if (!activityClasses.containsKey(activityName)) { // check if already processed
                if (!isAnnotatedActivity(enclosingElement)) {
                    error(annotatedElement, "@ActivityArg can only be used on fields in @ActivityScreen annotated activity (%s.%s)",
                            activityName, annotatedElement.getSimpleName());
                }
                if (!isValidActivityClass(enclosingElement)) {
                    return true;
                }
                activityClasses.put(activityName, new ActivityScreenAnnotatedClass(enclosingElement));
            }

            if (!isValidField(annotatedElement)) {
                error(annotatedElement, "@ActivityArg fields must not be private, protected, final or static (%s.%s)",
                        activityName, annotatedElement);
                continue;
            }

            ActivityScreenAnnotatedClass activityScreenAnnotatedClass = activityClasses.get(activityName);
            activityScreenAnnotatedClass.addFieldClass(new ActivityArgAnnotatedField(annotatedElement));

        }
        for (Element annotatedElement : roundEnv.getElementsAnnotatedWith(ActivityScreen.class)) {
            if (annotatedElement.getKind() != ElementKind.CLASS) {
                error(annotatedElement, "Only classes can be annotated with @%s", ActivityScreen.class.getSimpleName());
                return true;
            }
            TypeElement typeElement = (TypeElement) annotatedElement;
            Name activityName = typeElement.getQualifiedName();

            if (!activityClasses.containsKey(activityName)) { // check if already processed
                if (!isValidActivityClass(typeElement)) {
                    return true;
                }
                activityClasses.put(activityName, new ActivityScreenAnnotatedClass(typeElement));
            }
        }

        for (ActivityScreenAnnotatedClass activityScreenAnnotatedClass : activityClasses.values()) {
            try {
                activityScreenAnnotatedClass.generateCode(elementUtils, filer);
            } catch (IOException e) {
                error(null, e.getMessage());
            }
        }
        activityClasses.clear();
        
        return false;
    }

    /**
     * Prints an error message
     *
     * @param e The element which has caused the error. Can be null
     * @param msg The error message
     * @param args if the error message contains %s, %d etc. placeholders this arguments will be used
     * to
     * replace them
     */
    public void error(Element e, String msg, Object... args) {
        messager.printMessage(Diagnostic.Kind.ERROR, String.format(msg, args), e);
    }
}
