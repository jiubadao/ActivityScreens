package com.kboyarshinov.activityscreen.processor;

import com.google.common.collect.Iterables;
import com.squareup.javapoet.*;
import org.apache.commons.lang3.text.WordUtils;

import javax.annotation.processing.Filer;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.Name;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * @author Kirill Boyarshinov
 */
public final class CodeGenerator {
    private final ClassName intentClassName = ClassName.get("android.content", "Intent");
    private final ClassName activityClassName = ClassName.get("android.app", "Activity");
    private final ClassName bundleClassName = ClassName.get("android.os", "Bundle");

    private final Elements elementUtils;
    private final Types typeUtils;
    private final Filer filer;

    public CodeGenerator(Elements elementUtils, Types typeUtils, Filer filer) {
        this.elementUtils = elementUtils;
        this.typeUtils = typeUtils;
        this.filer = filer;
    }

    /**
     * Generate the java code
     *
     * @throws java.io.IOException
     */
    public void generate(Collection<ActivityScreenAnnotatedClass> annotatedClasses) throws IOException, UnsupportedTypeException {
        for (ActivityScreenAnnotatedClass annotatedClass : annotatedClasses) {
            TypeElement annotatedClassElement = annotatedClass.getTypeElement();
            TypeName activityTypeName = TypeName.get(annotatedClassElement.asType());
            Name activitySimpleName = annotatedClassElement.getSimpleName();
            String screenClassName = activitySimpleName + ActivityScreenAnnotatedClass.SUFFIX;
            PackageElement pkg = elementUtils.getPackageOf(annotatedClassElement);
            String packageName = pkg.isUnnamed() ? "" : pkg.getQualifiedName().toString();
            ClassName activityScreenClassName = ClassName.get(packageName, screenClassName);

            TypeSpec.Builder classBuilder = TypeSpec.classBuilder(screenClassName);

            // collect parameters
            Set<ActivityArgAnnotatedField> requiredFields = annotatedClass.getRequiredFields();
            Set<ActivityArgAnnotatedField> optionalFields = annotatedClass.getOptionalFields();
            List<Argument> requiredArguments = new ArrayList<Argument>(requiredFields.size());
            List<Argument> optionalArguments = new ArrayList<Argument>(optionalFields.size());
            for (ActivityArgAnnotatedField field : requiredFields) {
                requiredArguments.add(Argument.from(field, elementUtils, typeUtils));
            }
            for (ActivityArgAnnotatedField field : optionalFields) {
                optionalArguments.add(Argument.from(field, elementUtils, typeUtils));
            }

            // add fields
            for (Argument argument : requiredArguments) {
                classBuilder.addField(argument.asField(Modifier.PUBLIC, Modifier.FINAL));
            }
            for (Argument argument : optionalArguments) {
                classBuilder.addField(argument.asField(Modifier.PRIVATE));
            }

            // add constructor
            MethodSpec.Builder costructorBuilder = MethodSpec.constructorBuilder().addModifiers(Modifier.PUBLIC);
            for (Argument argument : requiredArguments) {
                costructorBuilder.addParameter(argument.asParameter());
                costructorBuilder.addStatement("this.$L = $L", argument.name, argument.name);
            }
            MethodSpec constructor = costructorBuilder.build();
            classBuilder.addMethod(constructor);

            // add setters
            for (Argument argument : optionalArguments) {
                MethodSpec setter = MethodSpec.methodBuilder(String.format("set%s", WordUtils.capitalize(argument.name))).
                        addStatement("this.$L = $L", argument.name, argument.name).
                        addStatement("return this").
                        returns(activityScreenClassName).
                    addModifiers(Modifier.PUBLIC).build();
                classBuilder.addMethod(setter);
            }

            // add getters
            for (Argument argument : optionalArguments) {
                MethodSpec getter = MethodSpec.methodBuilder(String.format("get%s", WordUtils.capitalize(argument.name))).
                        addStatement("return $L", argument.name).
                        returns(argument.typeName).
                        addModifiers(Modifier.PUBLIC).build();
                classBuilder.addMethod(getter);
            }

            // add open, openForResult, create methods
            MethodSpec openMethod = generateOpenMethod(false);
            MethodSpec openForResultMethod = generateOpenMethod(true);
            MethodSpec createIntentMethod = generateToIntentMethod(activitySimpleName, requiredArguments, optionalArguments);
            classBuilder.addMethod(openMethod).
                    addMethod(openForResultMethod).
                    addMethod(createIntentMethod);

            // add inject method if needed
            if (!requiredArguments.isEmpty() || !optionalArguments.isEmpty()) {
                MethodSpec injectMethod = generateInjectMethod(annotatedClassElement, Iterables.concat(requiredArguments, optionalArguments));
                classBuilder.addMethod(injectMethod);
            }

            // write class to file
            TypeSpec screenClass = classBuilder.addModifiers(Modifier.PUBLIC, Modifier.FINAL).build();
            JavaFile javaFile = JavaFile.builder(packageName, screenClass).indent("    ").build();
            javaFile.writeTo(filer);
        }
    }

    private MethodSpec generateOpenMethod(boolean forResult) {
        MethodSpec.Builder openMethodBuilder = MethodSpec.methodBuilder(forResult ? "openForResult" : "open").
                addModifiers(Modifier.PUBLIC).
                returns(void.class).
                addParameter(activityClassName, "activity");
        openMethodBuilder.addStatement("Intent intent = toIntent(activity)");
        if (forResult) {
            openMethodBuilder.addParameter(TypeName.INT, "requestCode");
            openMethodBuilder.addStatement("activity.startActivityForResult(intent, requestCode)");
        } else {
            openMethodBuilder.addStatement("activity.startActivity(intent)");
        }
        return openMethodBuilder.build();
    }

    private MethodSpec generateToIntentMethod(Name activitySimpleName, Iterable<Argument> requiredArguments, Iterable<Argument> optionalArguments) {
        MethodSpec.Builder createIntentBuilder = MethodSpec.methodBuilder("toIntent").
                addModifiers(Modifier.PUBLIC).
                addParameter(activityClassName, "activity").
                returns(intentClassName);
        createIntentBuilder.addStatement("$T intent = new $T(activity, $L.class)", intentClassName, intentClassName, activitySimpleName);
        for (Argument argument : requiredArguments) {
            createIntentBuilder.addStatement("intent.putExtra($S, $L)", argument.name, argument.name);
        }
        for (Argument argument : optionalArguments) {
            boolean primitive = argument.typeName.isPrimitive();
            if (!primitive)
                createIntentBuilder.beginControlFlow("if ($L != null)", argument.name);
            createIntentBuilder.addStatement("intent.putExtra($S, $L)", argument.name, argument.name);
            if (!primitive)
                createIntentBuilder.endControlFlow();
        }
        createIntentBuilder.addStatement("return intent");
        return createIntentBuilder.build();
    }

    private MethodSpec generateInjectMethod(TypeElement annotatedClassElement, Iterable<Argument> arguments) {
        TypeName activityTypeName = TypeName.get(annotatedClassElement.asType());
        MethodSpec.Builder injectBuilder = MethodSpec.methodBuilder("inject").
                addModifiers(Modifier.PUBLIC, Modifier.STATIC).
                addParameter(activityTypeName, "activity").
                returns(void.class);
        ClassName npe = ClassName.get(NullPointerException.class);
        injectBuilder.addStatement("$T bundle = activity.getIntent().getExtras()", bundleClassName);
        injectBuilder.beginControlFlow("if (bundle == null)");
        injectBuilder.addStatement("throw new $T(\"$T has empty Bundle. Use open() or openForResult() to launch activity.\")", npe, activityTypeName);
        injectBuilder.endControlFlow();
        for (Argument argument : arguments) {
            injectBuilder.addStatement("activity.$L = bundle.get$L($S)", argument.name, argument.type.name, argument.name);
        }
        return injectBuilder.build();
    }
}
