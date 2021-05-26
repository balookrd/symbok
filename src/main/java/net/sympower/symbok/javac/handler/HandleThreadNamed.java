/*
 * Copyright (C) 2009-2014 The Project Lombok Authors.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package net.sympower.symbok.javac.handler;

import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.JCAnnotation;
import com.sun.tools.javac.tree.JCTree.JCAssign;
import com.sun.tools.javac.tree.JCTree.JCBlock;
import com.sun.tools.javac.tree.JCTree.JCCompilationUnit;
import com.sun.tools.javac.tree.JCTree.JCExpression;
import com.sun.tools.javac.tree.JCTree.JCMethodDecl;
import com.sun.tools.javac.tree.JCTree.JCMethodInvocation;
import com.sun.tools.javac.tree.JCTree.JCStatement;
import com.sun.tools.javac.tree.JCTree.JCTry;
import com.sun.tools.javac.tree.JCTree.JCVariableDecl;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.List;
import lombok.core.AnnotationValues;
import lombok.javac.Javac;
import lombok.javac.JavacAnnotationHandler;
import lombok.javac.JavacNode;
import lombok.javac.JavacTreeMaker;
import net.sympower.symbok.ThreadNamed;
import org.kohsuke.MetaInfServices;

import static com.sun.tools.javac.code.Flags.ABSTRACT;
import static com.sun.tools.javac.code.Flags.FINAL;
import static com.sun.tools.javac.util.List.nil;
import static lombok.core.handlers.HandlerUtil.handleFlagUsage;
import static lombok.javac.handlers.JavacHandlerUtil.deleteAnnotationIfNeccessary;
import static lombok.javac.handlers.JavacHandlerUtil.genJavaLangTypeRef;
import static lombok.javac.handlers.JavacHandlerUtil.inNetbeansEditor;
import static lombok.javac.handlers.JavacHandlerUtil.isConstructorCall;
import static lombok.javac.handlers.JavacHandlerUtil.setGeneratedBy;
import static net.sympower.symbok.ConfigurationKeys.THREAD_NAMED_FLAG_USAGE;

/**
 * Handles the {@link ThreadNamed} annotation for javac.
 *
 * @author <a href="mailto:binkley@alumni.rice.edu">B. K. Oxley (binkley)</a>
 */
@MetaInfServices(JavacAnnotationHandler.class)
public class HandleThreadNamed extends JavacAnnotationHandler<ThreadNamed> {

  /**
   * lombok configuration: <pre>
   * hm.binkley.lombok.threadNamed.flagUsage} = WARNING | ERROR</pre>
   * If set, <em>any</em> usage of &#64;{@link ThreadNamed} results in a
   * warning/error.
   */
  @Override
  public void handle(
      final AnnotationValues<ThreadNamed> annotation,
      final JCAnnotation ast, final JavacNode annotationNode
  ) {
    handleFlagUsage(annotationNode, THREAD_NAMED_FLAG_USAGE,
                    "@ThreadNamed"
    );

    deleteAnnotationIfNeccessary(annotationNode, ThreadNamed.class);
    if (annotation.getInstance().value().isEmpty()) {
      annotationNode.addError("threadName cannot be the empty string.");
      return;
    }

    final JCAnnotation anno = (JCAnnotation) annotationNode.get();
    final List<JCExpression> args = anno.args;
    final JCExpression threadName = ((JCAssign) args.get(0))
        .getExpression(); // Must be present

    final JavacNode owner = annotationNode.up();
    switch (owner.getKind()) {
      case METHOD:
        handleMethod(annotationNode, (JCMethodDecl) owner.get(),
                     threadName
        );
        break;
      default:
        annotationNode.addError(
            "@ThreadNamed is legal only on methods and constructors.");
        break;
    }
  }

  private static void handleMethod(
      final JavacNode annotation,
      final JCMethodDecl method, final JCExpression threadName
  ) {
    final JavacNode methodNode = annotation.up();

    if (0 != (method.mods.flags & ABSTRACT)) {
      annotation.addError(
          "@ThreadNamed can only be used on concrete methods.");
      return;
    }

    if (null == method.body || method.body.stats.isEmpty()) {
      generateEmptyBlockWarning(annotation, false);
      return;
    }

    final JCStatement constructorCall = method.body.stats.get(0);
    final boolean isConstructorCall = isConstructorCall(constructorCall);
    final List<JCStatement> contents = isConstructorCall
                                       ? method.body.stats.tail : method.body.stats;

    if (null == contents || contents.isEmpty()) {
      generateEmptyBlockWarning(annotation, true);
      return;
    }

    final List<JCStatement> wrapped = List
        .of(renameThreadWhileIn(methodNode, contents, threadName,
                                method.params, annotation.get()
        ));
    method.body.stats = isConstructorCall ? List.of(constructorCall)
        .appendList(wrapped) : wrapped;
    methodNode.rebuild();
  }

  private static void generateEmptyBlockWarning(
      final JavacNode annotation,
      final boolean hasConstructorCall
  ) {
    if (hasConstructorCall) {
      annotation.addWarning(
          "Calls to sibling / super constructors are always "
              + "excluded from @ThreadNamed;"
              + " @ThreadNamed has been ignored because there "
              + "is no other code in "
              + "this constructor.");
    }
    else {
      annotation.addWarning(
          "This method or constructor is empty; @ThreadNamed has "
              + "been ignored.");
    }
  }

  private static JCStatement renameThreadWhileIn(
      final JavacNode node,
      final List<JCStatement> contents,
      final JCExpression threadNameFormat,
      final List<JCVariableDecl> params, final JCTree source
  ) {
    final String currentThreadVarName = "$currentThread";
    final String oldThreadNameVarName = "$oldThreadName";

    final JavacTreeMaker maker = node.getTreeMaker();
    final Context context = node.getContext();

    final JCVariableDecl saveCurrentThread = createCurrentThreadVar(node,
                                                                    maker, currentThreadVarName
    );
    final JCVariableDecl saveOldThreadName = createOldThreadNameVar(node,
                                                                    maker, currentThreadVarName, oldThreadNameVarName
    );

    final JCExpression threadName = threadName(node, maker,
                                               threadNameFormat, params
    );
    final JCStatement changeThreadName = setThreadName(node, maker,
                                                       threadName, currentThreadVarName
    );
    final JCStatement restoreOldThreadName = setThreadName(node, maker,
                                                           maker.Ident(node.toName(oldThreadNameVarName)),
                                                           currentThreadVarName
    );

    final JCBlock tryBlock = setGeneratedBy(maker.Block(0, contents),
                                            source, context
    );
    final JCTry wrapMethod = maker.Try(tryBlock, nil(),
                                       maker.Block(0, List.of(restoreOldThreadName))
    );

    if (inNetbeansEditor(node)) {
      //set span (start and end position) of the try statement and the
      // main block
      //this allows NetBeans to dive into the statement correctly:
      final JCCompilationUnit top = (JCCompilationUnit) node.top().get();
      final int startPos = contents.head.pos;
      final int endPos = Javac.getEndPosition(contents.last().pos(), top);
      tryBlock.pos = startPos;
      wrapMethod.pos = startPos;
      Javac.storeEnd(tryBlock, endPos, top);
      Javac.storeEnd(wrapMethod, endPos, top);
    }

    return setGeneratedBy(maker.Block(
        0,
        List.of(saveCurrentThread, saveOldThreadName,
                changeThreadName, wrapMethod
        )
                          ), source,
                          context
    );
  }

  private static JCVariableDecl createCurrentThreadVar(
      final JavacNode node,
      final JavacTreeMaker maker, final String currentThreadVarName
  ) {
    return maker.VarDef(maker.Modifiers(FINAL),
                        node.toName(currentThreadVarName),
                        genJavaLangTypeRef(node, "Thread"), maker.Apply(nil(),
                                                                        genJavaLangTypeRef(
                                                                            node,
                                                                            "Thread",
                                                                            "currentThread"
                                                                        ), nil()
        )
    );
  }

  private static JCVariableDecl createOldThreadNameVar(
      final JavacNode node,
      final JavacTreeMaker maker, final String currentThreadVarName,
      final String oldThreadNameVarName
  ) {
    return maker.VarDef(
        maker.Modifiers(FINAL),
        node.toName(oldThreadNameVarName),
        genJavaLangTypeRef(node, "String"),
        getThreadName(node, maker, currentThreadVarName)
    );
  }

  private static JCExpression threadName(
      final JavacNode node,
      final JavacTreeMaker maker, final JCExpression threadNameFormat,
      final List<JCVariableDecl> params
  ) {
    if (params.isEmpty()) {
      return threadNameFormat;
    }

    List<JCExpression> formatArgs = List.of(threadNameFormat);
    for (final JCVariableDecl param : params) {
      formatArgs = formatArgs.append(maker.Ident(param));
    }

    return maker.Apply(nil(),
                       maker.Select(
                           genJavaLangTypeRef(node, "String"),
                           node.toName("format")
                       ), formatArgs
    );
  }

  private static JCMethodInvocation getThreadName(
      final JavacNode node,
      final JavacTreeMaker maker, final String currentThreadVarNAme
  ) {
    return maker.Apply(nil(),
                       maker.Select(
                           maker.Ident(node.toName(currentThreadVarNAme)),
                           node.toName("getName")
                       ), nil()
    );
  }

  private static JCStatement setThreadName(
      final JavacNode node,
      final JavacTreeMaker maker, final JCExpression threadName,
      final String currentThreadVarName
  ) {
    return maker.Exec(maker.Apply(nil(),
                                  maker.Select(
                                      maker.Ident(node.toName(currentThreadVarName)),
                                      node.toName("setName")
                                  ), List.of(threadName)
    ));
  }
}
