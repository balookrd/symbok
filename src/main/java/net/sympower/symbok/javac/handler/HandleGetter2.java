package net.sympower.symbok.javac.handler;

import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.JCAnnotation;
import com.sun.tools.javac.tree.JCTree.JCBlock;
import com.sun.tools.javac.tree.JCTree.JCClassDecl;
import com.sun.tools.javac.tree.JCTree.JCExpression;
import com.sun.tools.javac.tree.JCTree.JCMethodDecl;
import com.sun.tools.javac.tree.JCTree.JCStatement;
import com.sun.tools.javac.tree.JCTree.JCTypeParameter;
import com.sun.tools.javac.tree.JCTree.JCVariableDecl;
import com.sun.tools.javac.util.JCDiagnostic.DiagnosticPosition;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.ListBuffer;
import com.sun.tools.javac.util.Name;
import lombok.AccessLevel;
import lombok.ConfigurationKeys;
import lombok.core.AST.Kind;
import lombok.core.AnnotationValues;
import lombok.core.configuration.CheckerFrameworkVersion;
import lombok.experimental.Delegate;
import lombok.javac.JavacAnnotationHandler;
import lombok.javac.JavacNode;
import lombok.javac.JavacTreeMaker;
import lombok.javac.JavacTreeMaker.TypeTag;
import net.sympower.symbok.Getter2;
import org.kohsuke.MetaInfServices;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static lombok.core.handlers.HandlerUtil.handleFlagUsage;
import static lombok.javac.Javac.CTC_BOOLEAN;
import static lombok.javac.Javac.CTC_BYTE;
import static lombok.javac.Javac.CTC_CHAR;
import static lombok.javac.Javac.CTC_DOUBLE;
import static lombok.javac.Javac.CTC_FLOAT;
import static lombok.javac.Javac.CTC_INT;
import static lombok.javac.Javac.CTC_LONG;
import static lombok.javac.Javac.CTC_SHORT;
import static lombok.javac.handlers.JavacHandlerUtil.CopyJavadoc;
import static lombok.javac.handlers.JavacHandlerUtil.addAnnotation;
import static lombok.javac.handlers.JavacHandlerUtil.cloneType;
import static lombok.javac.handlers.JavacHandlerUtil.copyJavadoc;
import static lombok.javac.handlers.JavacHandlerUtil.deleteAnnotationIfNeccessary;
import static lombok.javac.handlers.JavacHandlerUtil.deleteImportFromCompilationUnit;
import static lombok.javac.handlers.JavacHandlerUtil.findCopyableAnnotations;
import static lombok.javac.handlers.JavacHandlerUtil.genJavaLangTypeRef;
import static lombok.javac.handlers.JavacHandlerUtil.genTypeRef;
import static lombok.javac.handlers.JavacHandlerUtil.getCheckerFrameworkVersion;
import static lombok.javac.handlers.JavacHandlerUtil.getMirrorForFieldType;
import static lombok.javac.handlers.JavacHandlerUtil.hasAnnotation;
import static lombok.javac.handlers.JavacHandlerUtil.injectMethod;
import static lombok.javac.handlers.JavacHandlerUtil.isFieldDeprecated;
import static lombok.javac.handlers.JavacHandlerUtil.methodExists;
import static lombok.javac.handlers.JavacHandlerUtil.recursiveSetGeneratedBy;
import static lombok.javac.handlers.JavacHandlerUtil.toAllGetterNames;
import static lombok.javac.handlers.JavacHandlerUtil.toGetterName;
import static lombok.javac.handlers.JavacHandlerUtil.toJavacModifier;
import static lombok.javac.handlers.JavacHandlerUtil.typeMatches;
import static net.sympower.symbok.ConfigurationKeys.GETTER2_FLAG_USAGE;

/**
 * Handles the {@code lombok.Getter} annotation for javac.
 */
@MetaInfServices(JavacAnnotationHandler.class)
public class HandleGetter2 extends JavacAnnotationHandler<Getter2> {

  public void generateGetterForType(
      JavacNode typeNode,
      JavacNode errorNode,
      AccessLevel level,
      boolean checkForTypeLevelGetter
  ) {
    if (checkForTypeLevelGetter) {
      if (hasAnnotation(Getter2.class, typeNode)) {
        //The annotation will make it happen, so we can skip it.
        return;
      }
    }

    JCClassDecl typeDecl = null;
    if (typeNode.get() instanceof JCClassDecl) {
      typeDecl = (JCClassDecl) typeNode.get();
    }
    long modifiers = typeDecl == null ? 0 : typeDecl.mods.flags;
    boolean notAClass = (modifiers & (Flags.INTERFACE | Flags.ANNOTATION)) != 0;

    if (typeDecl == null || notAClass) {
      errorNode.addError("@Getter is only supported on a class, an enum, or a field.");
      return;
    }

    for (JavacNode field : typeNode.down()) {
      if (fieldQualifiesForGetterGeneration(field)) {
        generateGetterForField(field, errorNode.get(), level);
      }
    }
  }

  public static boolean fieldQualifiesForGetterGeneration(JavacNode field) {
    if (field.getKind() != Kind.FIELD) {
      return false;
    }
    JCVariableDecl fieldDecl = (JCVariableDecl) field.get();
    //Skip fields that start with $
    if (fieldDecl.name.toString().startsWith("$")) {
      return false;
    }
    //Skip static fields.
    if ((fieldDecl.mods.flags & Flags.STATIC) != 0) {
      return false;
    }
    return true;
  }

  /**
   * Generates a getter on the stated field.
   * <p>
   * The difference between this call and the handle method is as follows:
   * <p>
   * If there is a {@code lombok.Getter} annotation on the field, it is used and the
   * same rules apply (e.g. warning if the method already exists, stated access level applies).
   * If not, the getter is still generated if it isn't already there, though there will not
   * be a warning if its already there. The default access level is used.
   *
   * @param fieldNode The node representing the field you want a getter for.
   * @param pos       The node responsible for generating the getter (the {@code @Data} or {@code @Getter} annotation).
   */
  public void generateGetterForField(JavacNode fieldNode, DiagnosticPosition pos, AccessLevel level) {
    if (hasAnnotation(Getter2.class, fieldNode)) {
      //The annotation will make it happen, so we can skip it.
      return;
    }
    createGetterForField(level, fieldNode, fieldNode, false);
  }

  @Override
  public void handle(AnnotationValues<Getter2> annotation, JCAnnotation ast, JavacNode annotationNode) {
    handleFlagUsage(annotationNode, GETTER2_FLAG_USAGE, "@Getter2");

    Collection<JavacNode> fields = annotationNode.upFromAnnotationToFields();
    deleteAnnotationIfNeccessary(annotationNode, Getter2.class);
    deleteImportFromCompilationUnit(annotationNode, "lombok.AccessLevel");
    JavacNode node = annotationNode.up();
    Getter2 annotationInstance = annotation.getInstance();
    AccessLevel level = annotationInstance.value();

    if (level == AccessLevel.NONE) {
      return;
    }

    if (node == null) {
      return;
    }

    switch (node.getKind()) {
      case FIELD:
        createGetterForFields(level, fields, annotationNode, true);
        break;
      case TYPE:
        generateGetterForType(node, annotationNode, level, false);
        break;
    }
  }

  public void createGetterForFields(
      AccessLevel level,
      Collection<JavacNode> fieldNodes,
      JavacNode errorNode,
      boolean whineIfExists
  ) {
    for (JavacNode fieldNode : fieldNodes) {
      createGetterForField(level, fieldNode, errorNode, whineIfExists);
    }
  }

  public void createGetterForField(
      AccessLevel level,
      JavacNode fieldNode, JavacNode source, boolean whineIfExists
  ) {

    if (fieldNode.getKind() != Kind.FIELD) {
      source.addError("@Getter is only supported on a class or a field.");
      return;
    }

    JCVariableDecl fieldDecl = (JCVariableDecl) fieldNode.get();
    String methodName = toGetterName(fieldNode);

    if (methodName == null) {
      source.addWarning("Not generating getter for this field: It does not fit your @Accessors prefix list.");
      return;
    }

    for (String altName : toAllGetterNames(fieldNode)) {
      switch (methodExists(altName, fieldNode, false, 0)) {
        case EXISTS_BY_LOMBOK:
          return;
        case EXISTS_BY_USER:
          if (whineIfExists) {
            String altNameExpl = "";
            if (!altName.equals(methodName)) {
              altNameExpl = String.format(" (%s)", altName);
            }
            source.addWarning(
                String.format(
                    "Not generating %s(): A method with that name already exists%s",
                    methodName,
                    altNameExpl
                ));
          }
          return;
        default:
        case NOT_EXISTS:
          //continue scanning the other alt names.
      }
    }

    long access = toJavacModifier(level) | (fieldDecl.mods.flags & Flags.STATIC);

    injectMethod(
        fieldNode.up(),
        createGetter(access, fieldNode, fieldNode.getTreeMaker(), source.get()),
        List.<Type>nil(),
        getMirrorForFieldType(fieldNode)
    );
  }

  public JCMethodDecl createGetter(long access, JavacNode field, JavacTreeMaker treeMaker, JCTree source) {
    JCVariableDecl fieldNode = (JCVariableDecl) field.get();

    // Remember the type; lazy will change it
    JCExpression methodType = cloneType(treeMaker, copyType(treeMaker, fieldNode), source, field.getContext());
    // Generate the methodName; lazy will change the field type
    Name methodName = field.toName(toGetterName(field));

    List<JCStatement> statements;
    boolean addSuppressWarningsUnchecked = false;
    statements = createSimpleGetterBody(treeMaker, field);

    JCBlock methodBody = treeMaker.Block(0, statements);

    List<JCTypeParameter> methodGenericParams = List.nil();
    List<JCVariableDecl> parameters = List.nil();
    List<JCExpression> throwsClauses = List.nil();
    JCExpression annotationMethodDefaultValue = null;

    List<JCAnnotation> copyableAnnotations = findCopyableAnnotations(field);
    List<JCAnnotation> delegates = findDelegatesAndRemoveFromField(field);
    List<JCAnnotation> annsOnMethod = copyableAnnotations;
    if (field.isFinal()) {
      if (getCheckerFrameworkVersion(field).generatePure()) {
        annsOnMethod = annsOnMethod.prepend(treeMaker.Annotation(
            genTypeRef(field, CheckerFrameworkVersion.NAME__PURE),
            List.<JCExpression>nil()
        ));
      }
    }
    else {
      if (getCheckerFrameworkVersion(field).generateSideEffectFree()) {
        annsOnMethod =
            annsOnMethod.prepend(treeMaker.Annotation(
                genTypeRef(field, CheckerFrameworkVersion.NAME__SIDE_EFFECT_FREE),
                List.<JCExpression>nil()
            ));
      }
    }
    if (isFieldDeprecated(field)) {
      annsOnMethod =
          annsOnMethod.prepend(treeMaker.Annotation(genJavaLangTypeRef(field, "Deprecated"), List.<JCExpression>nil()));
    }

    JCMethodDecl decl =
        recursiveSetGeneratedBy(treeMaker.MethodDef(
            treeMaker.Modifiers(access, annsOnMethod),
            methodName,
            methodType,
            methodGenericParams,
            parameters,
            throwsClauses,
            methodBody,
            annotationMethodDefaultValue
        ), source, field.getContext());

    decl.mods.annotations = decl.mods.annotations.appendList(delegates);

    if (addSuppressWarningsUnchecked) {
      ListBuffer<JCExpression> suppressions = new ListBuffer<JCExpression>();
      if (!Boolean.FALSE.equals(field.getAst().readConfiguration(ConfigurationKeys.ADD_SUPPRESSWARNINGS_ANNOTATIONS))) {
        suppressions.add(treeMaker.Literal("all"));
      }
      suppressions.add(treeMaker.Literal("unchecked"));
      addAnnotation(
          decl.mods,
          field,
          source.pos,
          source,
          field.getContext(),
          "java.lang.SuppressWarnings",
          treeMaker.NewArray(null, List.<JCExpression>nil(), suppressions.toList())
      );
    }

    copyJavadoc(field, decl, CopyJavadoc.GETTER);
    return decl;
  }

  public static List<JCAnnotation> findDelegatesAndRemoveFromField(JavacNode field) {
    JCVariableDecl fieldNode = (JCVariableDecl) field.get();

    List<JCAnnotation> delegates = List.nil();
    for (JCAnnotation annotation : fieldNode.mods.annotations) {
      if (typeMatches(Delegate.class, field, annotation.annotationType)) {
        delegates = delegates.append(annotation);
      }
    }

    if (!delegates.isEmpty()) {
      ListBuffer<JCAnnotation> withoutDelegates = new ListBuffer<>();
      for (JCAnnotation annotation : fieldNode.mods.annotations) {
        if (!delegates.contains(annotation)) {
          withoutDelegates.append(annotation);
        }
      }
      fieldNode.mods.annotations = withoutDelegates.toList();
      field.rebuild();
    }
    return delegates;
  }

  public List<JCStatement> createSimpleGetterBody(JavacTreeMaker treeMaker, JavacNode field) {
    JCVariableDecl fieldDecl = (JCVariableDecl) field.get();
    return List.<JCStatement>of(treeMaker.Return(
        treeMaker.Select(treeMaker.Ident(field.toName("this")), fieldDecl.name)
    ));
  }

  private static final List<JCExpression> NIL_EXPRESSION = List.nil();

  public static final java.util.Map<TypeTag, String> TYPE_MAP;

  static {
    Map<TypeTag, String> m = new HashMap<>();
    m.put(CTC_INT, "Integer");
    m.put(CTC_DOUBLE, "Double");
    m.put(CTC_FLOAT, "Float");
    m.put(CTC_SHORT, "Short");
    m.put(CTC_BYTE, "Byte");
    m.put(CTC_LONG, "Long");
    m.put(CTC_BOOLEAN, "Boolean");
    m.put(CTC_CHAR, "Character");
    TYPE_MAP = Collections.unmodifiableMap(m);
  }

  public JCTree.JCMethodInvocation callGet(JavacNode source, JCExpression receiver) {
    JavacTreeMaker maker = source.getTreeMaker();
    return maker.Apply(NIL_EXPRESSION, maker.Select(receiver, source.toName("get")), NIL_EXPRESSION);
  }

  public JCStatement callSet(JavacNode source, JCExpression receiver, JCExpression value) {
    JavacTreeMaker maker = source.getTreeMaker();
    return maker.Exec(maker.Apply(
        NIL_EXPRESSION,
        maker.Select(receiver, source.toName("set")),
        List.<JCExpression>of(value)
    ));
  }

  public JCExpression copyType(JavacTreeMaker treeMaker, JCVariableDecl fieldNode) {
    return fieldNode.type != null ? treeMaker.Type(fieldNode.type) : fieldNode.vartype;
  }
}
