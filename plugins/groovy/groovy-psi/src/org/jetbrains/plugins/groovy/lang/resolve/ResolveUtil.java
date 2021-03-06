// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.jetbrains.plugins.groovy.lang.resolve;

import com.intellij.lang.java.beans.PropertyKind;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.Pair;
import com.intellij.psi.*;
import com.intellij.psi.scope.ElementClassHint;
import com.intellij.psi.scope.ElementClassHint.DeclarationKind;
import com.intellij.psi.scope.JavaScopeProcessorEvent;
import com.intellij.psi.scope.NameHint;
import com.intellij.psi.scope.PsiScopeProcessor;
import com.intellij.psi.util.*;
import com.intellij.util.containers.ContainerUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.groovy.GroovyLanguage;
import org.jetbrains.plugins.groovy.findUsages.LiteralConstructorReference;
import org.jetbrains.plugins.groovy.lang.lexer.GroovyTokenTypes;
import org.jetbrains.plugins.groovy.lang.psi.GrQualifiedReference;
import org.jetbrains.plugins.groovy.lang.psi.GroovyFile;
import org.jetbrains.plugins.groovy.lang.psi.GroovyFileBase;
import org.jetbrains.plugins.groovy.lang.psi.GroovyPsiElement;
import org.jetbrains.plugins.groovy.lang.psi.api.GroovyMethodResult;
import org.jetbrains.plugins.groovy.lang.psi.api.GroovyResolveResult;
import org.jetbrains.plugins.groovy.lang.psi.api.auxiliary.GrListOrMap;
import org.jetbrains.plugins.groovy.lang.psi.api.auxiliary.modifiers.annotation.GrAnnotation;
import org.jetbrains.plugins.groovy.lang.psi.api.signatures.GrSignature;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.*;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.arguments.GrArgumentList;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.arguments.GrNamedArgument;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.blocks.GrClosableBlock;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.GrBuiltinTypeClassExpression;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.GrExpression;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.GrMethodCall;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.GrReferenceExpression;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.path.GrCallExpression;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.path.GrIndexProperty;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.params.GrParameter;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.typedef.GrAnonymousClassDefinition;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.typedef.GrTypeDefinition;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.typedef.GrTypeDefinitionBody;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.typedef.members.GrAccessorMethod;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.typedef.members.GrGdkMethod;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.typedef.members.GrMember;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.typedef.members.GrMethod;
import org.jetbrains.plugins.groovy.lang.psi.api.toplevel.imports.GrImportStatement;
import org.jetbrains.plugins.groovy.lang.psi.api.types.GrCodeReferenceElement;
import org.jetbrains.plugins.groovy.lang.psi.api.util.GrStatementOwner;
import org.jetbrains.plugins.groovy.lang.psi.impl.GrClosureType;
import org.jetbrains.plugins.groovy.lang.psi.impl.GroovyResolveResultImpl;
import org.jetbrains.plugins.groovy.lang.psi.impl.signatures.GrClosureSignatureUtil;
import org.jetbrains.plugins.groovy.lang.psi.impl.statements.expressions.TypesUtil;
import org.jetbrains.plugins.groovy.lang.psi.impl.synthetic.GrLightParameter;
import org.jetbrains.plugins.groovy.lang.psi.impl.synthetic.GrScriptField;
import org.jetbrains.plugins.groovy.lang.psi.impl.synthetic.GroovyScriptClass;
import org.jetbrains.plugins.groovy.lang.psi.util.GdkMethodUtil;
import org.jetbrains.plugins.groovy.lang.psi.util.GrStaticChecker;
import org.jetbrains.plugins.groovy.lang.psi.util.GroovyCommonClassNames;
import org.jetbrains.plugins.groovy.lang.resolve.processors.*;
import org.jetbrains.plugins.groovy.lang.resolve.processors.inference.Argument;
import org.jetbrains.plugins.groovy.lang.resolve.processors.inference.MethodCandidate;

import java.util.*;

import static org.jetbrains.plugins.groovy.lang.psi.impl.GrAnnotationUtilKt.hasAnnotation;
import static org.jetbrains.plugins.groovy.lang.psi.util.PsiTreeUtilKt.treeWalkUpAndGetElement;
import static org.jetbrains.plugins.groovy.lang.resolve.ReceiverKt.processReceiverType;
import static org.jetbrains.plugins.groovy.lang.resolve.ResolveUtilKt.getDefaultConstructor;
import static org.jetbrains.plugins.groovy.lang.resolve.ResolveUtilKt.initialState;

/**
 * @author ven
 */
public class ResolveUtil {

  public static final PsiScopeProcessor.Event DECLARATION_SCOPE_PASSED = new PsiScopeProcessor.Event() {};
  public static final Key<String> DOCUMENTATION_DELEGATE_FQN = Key.create("groovy.documentation.delegate.fqn");

  private ResolveUtil() {
  }

  /**
   *
   * @param place - place to start tree walk up
   * @param processor
   * @param processNonCodeMembers - this parameter tells us if we need non code members
   * @return
   */
  public static boolean treeWalkUp(@NotNull PsiElement place, @NotNull PsiScopeProcessor processor, boolean processNonCodeMembers) {
    return ResolveUtilKt.treeWalkUp(place, processor, initialState(processNonCodeMembers));
  }

  public static boolean treeWalkUp(@NotNull final PsiElement place,
                                   @NotNull final PsiElement originalPlace,
                                   @NotNull final PsiScopeProcessor processor,
                                   @NotNull final ResolveState state) {
    final GrClosableBlock maxScope = ResolveUtilKt.processNonCodeMembers(state) ? PsiTreeUtil.getParentOfType(place, GrClosableBlock.class, true, PsiFile.class) : null;

    return PsiTreeUtil.treeWalkUp(place, maxScope, (scope, lastParent) -> {
      ProgressManager.checkCanceled();
      if (!doProcessDeclarations(originalPlace, lastParent, scope, substituteProcessor(processor, scope), state)) {
        return false;
      }
      issueLevelChangeEvents(processor, scope);
      return true;
    });
  }

  static boolean doProcessDeclarations(@NotNull PsiElement place,
                                       @Nullable PsiElement lastParent,
                                       @NotNull PsiElement scope,
                                       @NotNull PsiScopeProcessor processor,
                                       @NotNull ResolveState state) {
    boolean processNonCodeMembers = ResolveUtilKt.processNonCodeMembers(state);
    if (scope instanceof GrClosableBlock && processNonCodeMembers) {
      if (!((GrClosableBlock)scope).processClosureDeclarations(processor, state, lastParent, place)) return false;
    }
    else {
      if (scope instanceof PsiClass) {
        if (!processClassDeclarations((PsiClass)scope, processor, state, lastParent, place)) return false;
      } else {
        if (!scope.processDeclarations(processor, state, lastParent, place)) return false;
      }

      if (scope instanceof GrTypeDefinition || scope instanceof GrClosableBlock) {
        if (!processStaticImports(processor, place.getContainingFile(), state, place)) return false;
      }
    }

    if (processNonCodeMembers) {
      if (!processScopeNonCodeMembers(place, lastParent, processor, scope, state)) return false;
    }
    return true;
  }

  static void issueLevelChangeEvents(PsiScopeProcessor processor, PsiElement run) {
    processor.handleEvent(JavaScopeProcessorEvent.CHANGE_LEVEL, null);
    if (run instanceof GrClosableBlock && GrClosableBlock.OWNER_NAME.equals(getNameHint(processor)) ||
        run instanceof PsiClass && !(run instanceof PsiAnonymousClass) ||
        run instanceof GrMethod && run.getParent() instanceof GroovyFile) {
      processor.handleEvent(DECLARATION_SCOPE_PASSED, run);
    }
  }

  static PsiScopeProcessor substituteProcessor(PsiScopeProcessor processor, PsiElement scope) {
    //hack for walking up in java code
    //java's processDeclarations don't check names so we should do it manually
    if (scope.getLanguage() != GroovyLanguage.INSTANCE && processor.getHint(NameHint.KEY) != null) {
      return new JavaResolverProcessor(processor);
    }
    return processor;
  }

  private static boolean processScopeNonCodeMembers(@NotNull PsiElement place,
                                                    @Nullable PsiElement lastParent,
                                                    @NotNull PsiScopeProcessor processor,
                                                    @NotNull PsiElement scope,
                                                    @NotNull ResolveState state) {
    //state = ResolveState.initial();
    if (scope instanceof GrTypeDefinition) {
      if (!processNonCodeMembers(createPsiType((GrTypeDefinition)scope), processor, place, state)) return false;

      //@Category(CategoryType)
      //class Scope {...}
      PsiClassType categoryType = GdkMethodUtil.getCategoryType((PsiClass)scope);
      if (categoryType != null) {
        if (!processNonCodeMembers(categoryType, processor, place, state)) return false;
        if (!GdkMethodUtil.processCategoryMethods(place, processor, state, (PsiClass)scope)) return false;
      }

    }

    if (scope instanceof GroovyFileBase && ((GroovyFileBase)scope).isScript()) {
      final PsiClass psiClass = ((GroovyFileBase)scope).getScriptClass();
      if (psiClass != null) {
        if (!processNonCodeMembers(createPsiType(psiClass), processor, place, state)) return false;
      }
    }

    if (scope instanceof GrClosableBlock) {
      ResolveState _state = state.put(ClassHint.RESOLVE_CONTEXT, scope);

      PsiClass superClass = getLiteralSuperClass((GrClosableBlock)scope);
      if (superClass != null && !processClassDeclarations(superClass, processor, _state, null, place)) return false;

      if (!processNonCodeMembers(GrClosureType.create(((GrClosableBlock)scope), false), processor, place, _state)) return false;
    }

    if (scope instanceof GrStatementOwner) {
      if (!GdkMethodUtil.processMixinToMetaclass((GrStatementOwner)scope, processor, state, lastParent, place)) return false;
    }

    return true;
  }

  @NotNull
  private static PsiClassType createPsiType(@NotNull PsiClass psiClass) {
    PsiElementFactory factory = JavaPsiFacade.getElementFactory(psiClass.getProject());
    return factory.createType(psiClass);
  }

  @Nullable
  public static String getNameHint(PsiScopeProcessor processor) {
    NameHint nameHint = processor.getHint(NameHint.KEY);
    if (nameHint == null) {
      return null;
    }

    return nameHint.getName(ResolveState.initial());
  }

  public static boolean processElement(@NotNull PsiScopeProcessor processor,
                                       @NotNull PsiNamedElement namedElement,
                                       @NotNull ResolveState state) {
    NameHint nameHint = processor.getHint(NameHint.KEY);
    String name = nameHint == null ? null : nameHint.getName(state);
    if (name == null || name.equals(namedElement.getName())) {
      return processor.execute(namedElement, state);
    }

    return true;
  }

  public static boolean processAllDeclarations(@NotNull PsiType type,
                                               @NotNull PsiScopeProcessor processor,
                                               boolean processNonCodeMembers,
                                               @NotNull PsiElement place) {
    return processAllDeclarations(type, processor, initialState(processNonCodeMembers), place);
  }

  public static boolean processAllDeclarations(@NotNull PsiType type,
                                               @NotNull PsiScopeProcessor processor,
                                               @NotNull ResolveState state,
                                               @NotNull PsiElement place) {
    return processReceiverType(type, processor, state, place);
  }

  public static boolean processNonCodeMembers(@NotNull PsiType type,
                                              @NotNull PsiScopeProcessor processor,
                                              @NotNull PsiElement place,
                                              @NotNull ResolveState state) {
    if (type instanceof PsiEllipsisType) {
      type = ((PsiEllipsisType)type).toArrayType();
    }
    return NonCodeMembersContributor.runContributors(type, processor, place, state);
  }

  private static final Key<PsiType> COMPARABLE = Key.create(CommonClassNames.JAVA_LANG_COMPARABLE);
  private static final Key<PsiType> SERIALIZABLE = Key.create(CommonClassNames.JAVA_IO_SERIALIZABLE);
  private static final Key<PsiType> STRING = Key.create(CommonClassNames.JAVA_LANG_STRING);

  private static void collectSuperTypes(PsiType type, Set<? super String> visited, Project project) {
    String qName = rawCanonicalText(type);

    if (!visited.add(qName)) {
      return;
    }

    final PsiType[] superTypes = type.getSuperTypes();
    for (PsiType superType : superTypes) {
      collectSuperTypes(TypeConversionUtil.erasure(superType), visited, project);
    }

    if (type instanceof PsiArrayType && superTypes.length == 0) {
      PsiType comparable = createTypeFromText(project, COMPARABLE, CommonClassNames.JAVA_LANG_COMPARABLE);
      PsiType serializable = createTypeFromText(project, SERIALIZABLE, CommonClassNames.JAVA_IO_SERIALIZABLE);
      collectSuperTypes(comparable, visited, project);
      collectSuperTypes(serializable, visited, project);
    }

    if (GroovyCommonClassNames.GROOVY_LANG_GSTRING.equals(qName)) {
      collectSuperTypes(createTypeFromText(project, STRING, CommonClassNames.JAVA_LANG_STRING), visited, project);
    }

  }

  public static PsiType createTypeFromText(Project project, Key<PsiType> key, String text) {
    final PsiElementFactory factory = JavaPsiFacade.getElementFactory(project);
    PsiType type = project.getUserData(key);
    if (type == null) {
      type = factory.createTypeFromText(text, null);
      project.putUserData(key, type);
    }
    return type;
  }

  public static Set<String> getAllSuperTypes(@NotNull PsiType base, final Project project) {
    final Map<String, Set<String>> cache =
      CachedValuesManager.getManager(project).getCachedValue(project, () -> {
        final Map<String, Set<String>> result = ContainerUtil.newConcurrentMap();
        return CachedValueProvider.Result.create(result, PsiModificationTracker.JAVA_STRUCTURE_MODIFICATION_COUNT);
      });

    final PsiClass cls = PsiUtil.resolveClassInType(base);
    String key;
    if (cls instanceof PsiTypeParameter) {
      final PsiClass superClass = cls.getSuperClass();
      key = cls.getName() + (superClass == null ? CommonClassNames.JAVA_LANG_OBJECT : superClass.getName());
    }
    else if (base instanceof PsiClassType) {
      key = TypesUtil.getQualifiedName(base);
    }
    else {
      key = base.getCanonicalText();
    }
    Set<String> result = key == null ? null : cache.get(key);
    if (result == null) {
      result = ContainerUtil.newHashSet();
      collectSuperTypes(base, result, project);
      if (key != null) {
        cache.put(key, result);
      }
    }
    return result;
  }

  @NotNull
  private static String rawCanonicalText(@NotNull PsiType type) {
    if (type instanceof PsiClassType) {
      String qname = TypesUtil.getQualifiedName(type);
      if (qname != null) {
        return qname;
      }
    }
    return TypeConversionUtil.erasure(type).getCanonicalText();
  }

  public static GroovyPsiElement resolveProperty(GroovyPsiElement place, String name) {
    PropertyResolverProcessor processor = new PropertyResolverProcessor(name, place);
    return resolveExistingElement(place, processor, GrVariable.class, GrReferenceExpression.class);
  }

  @Nullable
  public static PsiClass resolveClass(GroovyPsiElement place, String name) {
    ClassResolverProcessor processor = new ClassResolverProcessor(name, place);
    return resolveExistingElement(place, processor, PsiClass.class);
  }

  @Nullable
  public static <T> T resolveExistingElement(PsiElement place, ResolverProcessor processor, Class<? extends T>... classes) {
    treeWalkUp(place, processor, true);
    final GroovyResolveResult[] candidates = processor.getCandidates();
    for (GroovyResolveResult candidate : candidates) {
      final PsiElement element = candidate.getElement();
      if (element == place) continue;
      for (Class<? extends T> clazz : classes) {
        if (clazz.isInstance(element)) //noinspection unchecked
          return (T)element;
      }
    }

    return null;
  }

  @NotNull
  public static Pair<GrStatement, GrLabeledStatement> resolveLabelTargets(@Nullable String labelName,
                                                                          @Nullable PsiElement element,
                                                                          boolean isBreak) {
    if (element == null) return new Pair<>(null, null);

    if (labelName == null) {
      do {
        element = element.getContext();
        if (element == null || element instanceof GrClosableBlock || element instanceof GrMember || element instanceof GroovyFile) {
          return new Pair<>(null, null);
        }
      }
      while (!(element instanceof GrLoopStatement) && !(isBreak && element instanceof GrSwitchStatement));
      return new Pair<>(((GrStatement)element), null);
    }

    GrStatement statement = null;
    do {
      PsiElement last = element;
      element = element.getContext();
      if (element == null || element instanceof GrMember || element instanceof GroovyFile) break;
      if (element instanceof GrStatement && !(element instanceof GrClosableBlock)) {
        statement = (GrStatement)element;
      }
      PsiElement sibling = element;
      while (sibling != null) {
        final GrLabeledStatement labelStatement = findLabelStatementIn(sibling, last, labelName);
        if (labelStatement != null) {
          return Pair.create(statement, labelStatement);
        }
        sibling = sibling.getPrevSibling();
      }
      if (element instanceof GrClosableBlock) break;
    }
    while (true);
    return new Pair<>(null, null);
  }

  private static boolean isApplicableLabelStatement(PsiElement element, String labelName) {
    return ((element instanceof GrLabeledStatement && labelName.equals(((GrLabeledStatement)element).getName())));
  }

  @Nullable
  private static GrLabeledStatement findLabelStatementIn(PsiElement element, PsiElement lastChild, String labelName) {
    if (isApplicableLabelStatement(element, labelName)) {
      return (GrLabeledStatement)element;
    }
    for (PsiElement child = element.getFirstChild(); child != null && child != lastChild; child = child.getNextSibling()) {
      final GrLabeledStatement statement = findLabelStatementIn(child, child, labelName);
      if (statement != null) return statement;
    }
    return null;
  }

  @Nullable
  public static GrLabeledStatement resolveLabeledStatement(@Nullable String labelName, @Nullable PsiElement element, boolean isBreak) {
    return resolveLabelTargets(labelName, element, isBreak).second;
  }

  @Nullable
  public static GrStatement resolveLabelTargetStatement(@Nullable String labelName, @Nullable PsiElement element, boolean isBreak) {
    return resolveLabelTargets(labelName, element, isBreak).first;
  }

  public static boolean processCategoryMembers(@NotNull PsiElement place,
                                               @NotNull PsiScopeProcessor processor,
                                               @NotNull ResolveState state) {
    PsiElement run = place;
    PsiElement lastParent = null;

    while (run != null) {
      ProgressManager.checkCanceled();
      if (run instanceof GrClosableBlock) {
        PsiClass superClass = getLiteralSuperClass((GrClosableBlock)run);
        if (superClass != null && !GdkMethodUtil.processCategoryMethods(run, processor, state, superClass)) return false;
      }

      if (run instanceof GrStatementOwner) {
        if (!GdkMethodUtil.processMixinToMetaclass((GrStatementOwner)run, processor, state, lastParent, place)) return false;
      }

      lastParent = run;
      run = run.getContext();
    }

    return true;
  }

  @Nullable
  private static PsiClass getLiteralSuperClass(GrClosableBlock closure) {
    PsiClassType type;
    if (closure.getParent() instanceof GrNamedArgument && closure.getParent().getParent() instanceof GrListOrMap) {
      type = LiteralConstructorReference.getTargetConversionType((GrListOrMap)closure.getParent().getParent());
    }
    else {
      type = LiteralConstructorReference.getTargetConversionType(closure);
    }
    return type != null ? type.resolve() : null;
  }

  public static PsiElement[] mapToElements(GroovyResolveResult[] candidates) {
    PsiElement[] elements = new PsiElement[candidates.length];
    for (int i = 0; i < elements.length; i++) {
      elements[i] = candidates[i].getElement();
    }

    return elements;
  }

  /**
   * The point is that we do not want to see repeating methods in completion.
   * Candidates can have multiple toString() methods (e.g. from Object and from some inheritor) and we want to show only one.
   */
  public static GroovyResolveResult[] filterSameSignatureCandidates(Collection<? extends GroovyResolveResult> candidates) {
    if (candidates.size() == 0) return GroovyResolveResult.EMPTY_ARRAY;
    if (candidates.size() == 1) return candidates.toArray(GroovyResolveResult.EMPTY_ARRAY);

    final List<GroovyResolveResult> result = new ArrayList<>();

    final Iterator<? extends GroovyResolveResult> allIterator = candidates.iterator();
    result.add(allIterator.next());

    Outer:
    while (allIterator.hasNext()) {
      final GroovyResolveResult currentResult = allIterator.next();

      final PsiMethod currentMethod;
      final PsiSubstitutor currentSubstitutor;
      if (currentResult instanceof GroovyMethodResult) {
        final GroovyMethodResult currentMethodResult = (GroovyMethodResult)currentResult;
        currentMethod = currentMethodResult.getElement();
        currentSubstitutor = currentMethodResult.getPartialSubstitutor();
      }
      else if (currentResult.getElement() instanceof PsiMethod) {
        currentMethod = (PsiMethod)currentResult.getElement();
        currentSubstitutor = currentResult.getSubstitutor();
      }
      else {
        result.add(currentResult);
        continue;
      }

      Inner:
      for (Iterator<GroovyResolveResult> resultIterator = result.iterator(); resultIterator.hasNext(); ) {
        final GroovyResolveResult otherResult = resultIterator.next();

        final PsiMethod otherMethod;
        final PsiSubstitutor otherSubstitutor;
        if (otherResult instanceof GroovyMethodResult) {
          final GroovyMethodResult otherMethodResult = (GroovyMethodResult)otherResult;
          otherMethod = otherMethodResult.getElement();
          otherSubstitutor = otherMethodResult.getPartialSubstitutor();
        }
        else if (otherResult.getElement() instanceof PsiMethod) {
          otherMethod = (PsiMethod)otherResult.getElement();
          otherSubstitutor = otherResult.getSubstitutor();
        }
        else {
          continue Inner;
        }

        if (dominated(currentMethod, currentSubstitutor, otherMethod, otherSubstitutor)) {
          // if current method is dominated by other method
          // then do not add current method to result and skip rest other methods
          continue Outer;
        }
        else if (dominated(otherMethod, otherSubstitutor, currentMethod, currentSubstitutor)) {
          // if other method is dominated by current method
          // then remove other from result
          resultIterator.remove();
        }
      }

      result.add(currentResult);
    }

    return result.toArray(GroovyResolveResult.EMPTY_ARRAY);
  }

  public static boolean dominated(PsiMethod method1,
                                  PsiSubstitutor substitutor1,
                                  PsiMethod method2,
                                  PsiSubstitutor substitutor2) {  //method1 has more general parameter types then method2
    if (!method1.getName().equals(method2.getName())) return false;

    PsiParameter[] params1 = method1.getParameterList().getParameters();
    PsiParameter[] params2 = method2.getParameterList().getParameters();

    if (params1.length != params2.length) return false;

    for (int i = 0; i < params2.length; i++) {
      PsiType type1 = TypeConversionUtil.erasure(substitutor1.substitute(params1[i].getType()));
      PsiType type2 = TypeConversionUtil.erasure(substitutor2.substitute(params2[i].getType()));
      if (!type1.equals(type2)) return false;
    }

    if (method1 instanceof GrGdkMethod && method2 instanceof GrGdkMethod) {
      PsiType t1 = substitutor1.substitute(((GrGdkMethod)method1).getReceiverType());
      PsiType t2 = substitutor2.substitute(((GrGdkMethod)method2).getReceiverType());

      if (!t1.equals(t2)) {
        if (t1 instanceof PsiClassType) t1 = TypeConversionUtil.erasure(t1);
        if (t2 instanceof PsiClassType) t2 = TypeConversionUtil.erasure(t2);
        //method1 is more general than method2
        return t1.isAssignableFrom(t2);
      }
    }

    return true;
  }

  public static GroovyResolveResult[] getCallVariants(GroovyPsiElement place) {
    final PsiElement parent = place.getParent();
    GroovyResolveResult[] variants = GroovyResolveResult.EMPTY_ARRAY;
    if (parent instanceof GrCallExpression) {
      variants = ((GrCallExpression)parent).getCallVariants(place instanceof GrExpression ? (GrExpression)place : null);
    }
    else if (parent instanceof GrConstructorInvocation) {
      final PsiClass clazz = ((GrConstructorInvocation)parent).getDelegatedClass();
      if (clazz != null) {
        final PsiMethod[] constructors = clazz.getConstructors();
        variants = getConstructorResolveResult(constructors, place);
      }
    }
    else if (parent instanceof GrAnonymousClassDefinition) {
      final PsiElement element = ((GrAnonymousClassDefinition)parent).getBaseClassReferenceGroovy().resolve();
      if (element instanceof PsiClass) {
        final PsiMethod[] constructors = ((PsiClass)element).getConstructors();
        variants = getConstructorResolveResult(constructors, place);
      }
    }
    else if (place instanceof GrReferenceExpression) {
      variants = ((GrReferenceExpression)place).getSameNameVariants();
    }
    return variants;
  }

  private static GroovyResolveResult[] getConstructorResolveResult(PsiMethod[] constructors, PsiElement place) {
    GroovyResolveResult[] variants = new GroovyResolveResult[constructors.length];
    for (int i = 0; i < constructors.length; i++) {
      final boolean isAccessible = PsiUtil.isAccessible(constructors[i], place, null);
      variants[i] = new GroovyResolveResultImpl(constructors[i], isAccessible);
    }
    return variants;
  }

  public static GroovyResolveResult[] getAllClassConstructors(@NotNull PsiClass psiClass,
                                                              @NotNull PsiSubstitutor substitutor,
                                                              @Nullable PsiType[] argTypes,
                                                              @NotNull PsiElement place) {
    final MethodResolverProcessor processor = new MethodResolverProcessor(psiClass.getName(), place, true, null, argTypes, PsiType.EMPTY_ARRAY);
    final ResolveState state = ResolveState.initial().put(PsiSubstitutor.KEY, substitutor);

    final PsiMethod[] constructors = psiClass.getConstructors();
    if (constructors.length == 0) {
      PsiMethod defaultConstructor = getDefaultConstructor(psiClass);
      processor.execute(defaultConstructor, state);
    }
    else {
      for (PsiMethod constructor : constructors) {
        processor.execute(constructor, state);
      }
    }

    final PsiClassType qualifierType = JavaPsiFacade.getElementFactory(psiClass.getProject()).createType(psiClass);
    processNonCodeMembers(qualifierType, processor, place, state);
    return processor.getCandidates();
  }

  public static boolean isDefinitelyKeyOfMap(GrReferenceExpression ref) {
    final GrExpression qualifier = getSelfOrWithQualifier(ref);
    if (qualifier == null) return false;
    //key in 'java.util.Map.key' is not access to map, it is access to static property of field
    if (qualifier instanceof GrReferenceExpression && ((GrReferenceExpression)qualifier).resolve() instanceof PsiClass) return false;
    if (ref.getDotTokenType() == GroovyTokenTypes.mSPREAD_DOT) return false;

    final PsiType type = qualifier.getType();
    if (!InheritanceUtil.isInheritor(type, CommonClassNames.JAVA_UTIL_MAP)) return false;

    final String qname = TypesUtil.getQualifiedName(type);
    return !GroovyCommonClassNames.GROOVY_UTIL_CONFIG_OBJECT.equals(qname);
  }

  public static boolean isKeyOfMap(GrReferenceExpression ref) {
    if (!(ref.getParent() instanceof GrIndexProperty) && org.jetbrains.plugins.groovy.lang.psi.util.PsiUtil.isCall(ref)) return false;
    if (ref.multiResolve(false).length > 0) return false;
    return mayBeKeyOfMap(ref);
  }

  public static boolean mayBeKeyOfMap(GrReferenceExpression ref) {
    final GrExpression qualifier = getSelfOrWithQualifier(ref);
    if (qualifier == null) return false;
    if (qualifier instanceof GrReferenceExpression && ((GrReferenceExpression)qualifier).resolve() instanceof PsiClass) return false;
    if (ref.getDotTokenType() == GroovyTokenTypes.mSPREAD_DOT) return false;
    return InheritanceUtil.isInheritor(qualifier.getType(), CommonClassNames.JAVA_UTIL_MAP);
  }


  @Nullable
  public static GrExpression getSelfOrWithQualifier(GrReferenceExpression ref) {
    final GrExpression qualifier = ref.getQualifierExpression();
    if (qualifier != null) {
      return qualifier;
    }

    PsiElement place = ref;
    while (true) {
      final GrClosableBlock closure = PsiTreeUtil.getParentOfType(place, GrClosableBlock.class, true, GrMember.class, GroovyFile.class);
      if (closure == null) break;
      place = closure;
      PsiElement clParent = closure.getParent();
      if (clParent instanceof GrArgumentList) clParent = clParent.getParent();
      if (!(clParent instanceof GrMethodCall)) continue;
      final GrExpression expression = ((GrMethodCall)clParent).getInvokedExpression();
      if (expression instanceof GrReferenceExpression &&
          GdkMethodUtil.isWithName(((GrReferenceExpression)expression).getReferenceName()) &&
          ((GrReferenceExpression)expression).resolve() instanceof GrGdkMethod) {
        final GrExpression withQualifier = ((GrReferenceExpression)expression).getQualifierExpression();
        if (withQualifier != null) {
          return withQualifier;
        }
      }
    }
    return null;
  }


  @NotNull
  public static GroovyResolveResult[] getMethodCandidates(@NotNull PsiType thisType,
                                                          @Nullable String methodName,
                                                          @NotNull PsiElement place,
                                                          @Nullable PsiType... argumentTypes) {
    return getMethodCandidates(thisType, methodName, place, false, argumentTypes);
  }

  @NotNull
  public static GroovyResolveResult[] getMethodCandidates(@NotNull PsiType thisType,
                                                          @Nullable String methodName,
                                                          @NotNull PsiElement place,
                                                          boolean allVariants,
                                                          @Nullable PsiType... argumentTypes) {
    if (methodName == null) return GroovyResolveResult.EMPTY_ARRAY;
    thisType = TypesUtil.boxPrimitiveType(thisType, place.getManager(), place.getResolveScope());
    MethodResolverProcessor processor =
      new MethodResolverProcessor(methodName, place, false, thisType, argumentTypes, PsiType.EMPTY_ARRAY, allVariants);
    final ResolveState state = ResolveState.initial().put(ClassHint.RESOLVE_CONTEXT, place);
    processAllDeclarations(thisType, processor, state, place);
    boolean hasApplicableMethods = processor.hasApplicableCandidates();
    final GroovyResolveResult[] methodCandidates = processor.getCandidates();
    if (hasApplicableMethods && methodCandidates.length == 1) return methodCandidates;

    PropertyResolverProcessor propertyResolver = new PropertyResolverProcessor(methodName, place);
    processAllDeclarations(thisType, propertyResolver, state, place);
    final GroovyResolveResult[] allPropertyCandidates = propertyResolver.getCandidates();

    List<GroovyResolveResult> propertyCandidates = new ArrayList<>(allPropertyCandidates.length);
    for (GroovyResolveResult candidate : allPropertyCandidates) {
      final PsiElement resolved = candidate.getElement();
      if (!(resolved instanceof GrField)) continue;
      final PsiType type = ((GrField)resolved).getTypeGroovy();
      if (isApplicableClosureType(type, argumentTypes, place)) {
        propertyCandidates.add(candidate);
      }
    }

    for (GroovyResolveResult candidate : propertyCandidates) {
      final PsiElement element = candidate.getElement();
      if (element instanceof GrField) {
        final PsiClass containingClass = ((PsiField)element).getContainingClass();
        if (containingClass != null && PsiTreeUtil.isContextAncestor(containingClass, place, true)) {
          return new GroovyResolveResult[]{candidate};
        }
      }
    }

    List<GroovyResolveResult> allCandidates = new ArrayList<>();
    if (hasApplicableMethods) {
      ContainerUtil.addAll(allCandidates, methodCandidates);
    }
    ContainerUtil.addAll(allCandidates, propertyCandidates);

    //search for getters
    for (PropertyKind kind : Arrays.asList(PropertyKind.GETTER, PropertyKind.BOOLEAN_GETTER)) {
      PropertyProcessor propertyProcessor = new PropertyProcessor(methodName, kind, () -> PsiType.EMPTY_ARRAY, place);
      processAllDeclarations(thisType, propertyProcessor, state, place);
      final List<GroovyResolveResult> candidates = propertyProcessor.getResults(); //can be only one candidate
      final List<GroovyResolveResult> applicable = new ArrayList<>();
      for (GroovyResolveResult candidate : candidates) {
        PsiMethod method = (PsiMethod)candidate.getElement();
        assert method != null;
        final PsiType type = org.jetbrains.plugins.groovy.lang.psi.util.PsiUtil.getSmartReturnType(method);
        if (isApplicableClosureType(type, argumentTypes, place)) {
          applicable.add(candidate);
        }
      }
      if (applicable.size() == 1) {
        return applicable.toArray(GroovyResolveResult.EMPTY_ARRAY);
      }
      ContainerUtil.addAll(allCandidates, applicable);
    }

    if (!allCandidates.isEmpty()) {
      return allCandidates.toArray(GroovyResolveResult.EMPTY_ARRAY);
    }
    else if (!hasApplicableMethods) {
      return methodCandidates;
    }
    return GroovyResolveResult.EMPTY_ARRAY;
  }

  private static boolean isApplicableClosureType(@Nullable PsiType type, @Nullable PsiType[] argTypes, @NotNull PsiElement place) {
    if (!(type instanceof GrClosureType)) return false;
    if (argTypes == null) return true;

    final List<GrSignature> signature = ((GrClosureType)type).getSignatures();
    return GrClosureSignatureUtil.isSignatureApplicable(signature, argTypes, place);
  }

  @Nullable
  public static PsiType extractReturnTypeFromCandidate(GroovyResolveResult candidate, GrExpression expression, @Nullable PsiType[] args) {
    final PsiElement element = candidate.getElement();
    if (element instanceof PsiMethod && !candidate.isInvokedOnProperty()) {
      return TypesUtil.substituteAndNormalizeType(org.jetbrains.plugins.groovy.lang.psi.util.PsiUtil.getSmartReturnType((PsiMethod)element),
                                                  candidate.getSubstitutor(), candidate.getSpreadState(), expression);
    }

    final PsiType type;
    if (element instanceof GrField) {
      type = ((GrField)element).getTypeGroovy();
    }
    else if (element instanceof PsiMethod) {
      type = org.jetbrains.plugins.groovy.lang.psi.util.PsiUtil.getSmartReturnType((PsiMethod)element);
    }
    else {
      return null;
    }
    if (type instanceof GrClosureType) {
      final List<GrSignature> signature = ((GrClosureType)type).getSignatures();
      PsiType returnType = GrClosureSignatureUtil.getReturnType(signature, args, expression);
      return TypesUtil.substituteAndNormalizeType(returnType, candidate.getSubstitutor(), candidate.getSpreadState(), expression);
    }
    return null;
  }

  public static boolean isEnumConstant(PsiReference ref, String name, String qName) {
    PsiElement resolved = ref.resolve();
    if (!(resolved instanceof PsiEnumConstant)) return false;
    if (!name.equals(((PsiEnumConstant)resolved).getName())) return false;

    PsiClass aClass = ((PsiEnumConstant)resolved).getContainingClass();
    if (aClass == null) return false;
    return qName.equals(aClass.getQualifiedName());
  }

  public static boolean isScriptField(GrVariable var) {
    PsiElement parent = var.getParent();
    return parent instanceof GrVariableDeclaration && isScriptFieldDeclaration(((GrVariableDeclaration)parent));
  }

  public static boolean isScriptFieldDeclaration(@NotNull GrVariableDeclaration declaration) {
    PsiFile containingFile = declaration.getContainingFile();
    if (!(containingFile instanceof GroovyFile) || !((GroovyFile)containingFile).isScript()) return false;

    GrMember member = PsiTreeUtil.getParentOfType(declaration, GrTypeDefinition.class, GrMethod.class);
    if (member != null) return false;

    return hasAnnotation(declaration.getModifierList(), GroovyCommonClassNames.GROOVY_TRANSFORM_FIELD);
  }

  public static boolean isFieldDeclaration(@NotNull GrVariableDeclaration declaration) {
    return declaration.getParent() instanceof GrTypeDefinitionBody || isScriptFieldDeclaration(declaration);
  }

  @Nullable
  public static GrScriptField findScriptField(@NotNull GrVariable var) {
    return CachedValuesManager.getCachedValue(var, () -> {
      PsiFile file = var.getContainingFile();
      if (file instanceof GroovyFile && ((GroovyFile)file).isScript()) {
        PsiClass scriptClass = ((GroovyFile)file).getScriptClass();
        assert scriptClass != null;
        for (PsiField field : scriptClass.getFields()) {
          if (field instanceof GrScriptField) {
            if (((GrScriptField)field).getOriginalVariable() == var) return CachedValueProvider.Result.create(((GrScriptField)field), var);
          }
        }
      }
      return CachedValueProvider.Result.create(null, var);
    });
  }

  @Nullable
  public static PsiClass resolveAnnotation(PsiElement insideAnnotation) {
    final GrAnnotation annotation = PsiTreeUtil.getParentOfType(insideAnnotation, GrAnnotation.class, false);
    if (annotation == null) return null;

    final GrCodeReferenceElement reference = annotation.getClassReference();
    final GroovyResolveResult result = reference.advancedResolve();
    final PsiElement element = result.getElement();
    if (element instanceof PsiClass && ((PsiClass)element).isAnnotationType()) return (PsiClass)element;
    return null;
  }

  public static PsiNamedElement findDuplicate(@NotNull GrVariable variable) {
    if (isScriptField(variable)) {
      final String name = variable.getName();

      final GroovyScriptClass script = (GroovyScriptClass)((GroovyFile)variable.getContainingFile()).getScriptClass();
      assert script != null;
      List<GrField> duplicates = ContainerUtil.filter(script.getFields(), (GrField f) -> {
        if (!(f instanceof GrScriptField)) return false;
        if (!name.equals(f.getName())) return false;
        if (((GrScriptField)f).getOriginalVariable() == variable) return false;
        return true;
      });

      return duplicates.size() > 0 ? duplicates.get(0) : null;
    }
    else {
      PsiNamedElement duplicate = treeWalkUpAndGetElement(variable, new DuplicateVariableProcessor(variable));
      final PsiElement context1 = variable.getContext();
      if (duplicate == null && variable instanceof GrParameter && context1 != null) {
        final PsiElement context = context1.getContext();
        if (context instanceof GrClosableBlock ||
            context instanceof GrMethod && !(context.getParent() instanceof GroovyFile) ||
            context instanceof GrTryCatchStatement) {
          duplicate = treeWalkUpAndGetElement(context.getParent(), new DuplicateVariableProcessor(variable));
        }
      }
      if (duplicate instanceof GrLightParameter && "args".equals(duplicate.getName())) {
        return null;
      }
      else {
        return duplicate;
      }
    }
  }

  public static boolean canBeClassOrPackage(final GrReferenceExpression ref) {
    GrExpression qualifier = ref.getQualifier();
    if (qualifier instanceof GrReferenceExpression) {
      final PsiElement resolvedQualifier = ((GrReferenceExpression)qualifier).resolve();
      return resolvedQualifier instanceof PsiClass || resolvedQualifier instanceof PsiPackage;
    }
    else {
      return qualifier == null;
    }
  }

  @NotNull
  public static List<Pair<PsiParameter, PsiType>> collectExpectedParamsByArg(@NotNull PsiElement place,
                                                                             @NotNull GroovyResolveResult[] variants,
                                                                             @NotNull GrNamedArgument[] namedArguments,
                                                                             @NotNull GrExpression[] expressionArguments,
                                                                             @NotNull GrClosableBlock[] closureArguments,
                                                                             @NotNull GrExpression arg) {
    List<Pair<PsiParameter, PsiType>> expectedParams = ContainerUtil.newArrayList();

    for (GroovyResolveResult variant : variants) {
      if (variant instanceof GroovyMethodResult && ((GroovyMethodResult)variant).getCandidate() != null) {
        MethodCandidate candidate = ((GroovyMethodResult)variant).getCandidate();
        if (candidate != null) {
          Pair<PsiParameter, PsiType> pair = candidate.completionMapArguments().get(new Argument(null, arg));
          ContainerUtil.addIfNotNull(expectedParams, pair);
        }
      } else {
        final Map<GrExpression, Pair<PsiParameter, PsiType>> map = GrClosureSignatureUtil.mapArgumentsToParameters(
          variant, place, true, true, namedArguments, expressionArguments, closureArguments
        );

        if (map != null) {
          final Pair<PsiParameter, PsiType> pair = map.get(arg);
          ContainerUtil.addIfNotNull(expectedParams, pair);
        }
      }
    }
    return expectedParams;
  }

  public static boolean shouldProcessClasses(ElementClassHint classHint) {
    return classHint == null || classHint.shouldProcess(DeclarationKind.CLASS);
  }

  public static boolean shouldProcessMethods(ElementClassHint classHint) {
    return classHint == null || classHint.shouldProcess(DeclarationKind.METHOD);
  }

  public static boolean shouldProcessProperties(ElementClassHint classHint) {
    return classHint == null || classHint.shouldProcess(DeclarationKind.VARIABLE)
           || classHint.shouldProcess(DeclarationKind.FIELD) || classHint.shouldProcess(DeclarationKind.ENUM_CONST);
  }

  public static boolean processStaticImports(@NotNull PsiScopeProcessor resolver,
                                             @NotNull PsiFile file,
                                             @NotNull ResolveState state,
                                             @NotNull PsiElement place) {
    if (!shouldProcessMethods(resolver.getHint(ElementClassHint.KEY))) return true;

    return file.processDeclarations(new GrDelegatingScopeProcessorWithHints(resolver, null, ClassHint.RESOLVE_KINDS_METHOD) {
      @Override
      public boolean execute(@NotNull PsiElement element, @NotNull ResolveState _state) {
        if (_state.get(ClassHint.RESOLVE_CONTEXT) instanceof GrImportStatement) {
          super.execute(element, _state);
        }
        return true;
      }
    }, state, null, place);
  }

  public static boolean resolvesToClass(@Nullable PsiElement expression) {
    if (!(expression instanceof GrQualifiedReference)) return false;
    return isClassReference(expression) || ((GrQualifiedReference)expression).resolve() instanceof PsiClass;
  }

  public static boolean isClassReference(@NotNull PsiElement expression) {
    if (!(expression instanceof GrReferenceExpression)) return false;
    GrReferenceExpression ref = (GrReferenceExpression)expression;
    GrExpression qualifier = ref.getQualifier();
    if (!"class".equals(ref.getReferenceName())) return false;
    return qualifier != null && getClassReferenceFromExpression(qualifier) != null;
  }


  @Nullable
  public static PsiType getClassReferenceFromExpression(@NotNull PsiElement expression) {

    if (expression instanceof GrReferenceExpression) {
      if (org.jetbrains.plugins.groovy.lang.psi.util.PsiUtil.isThisReference(expression)) return null;

      PsiElement resolved = ((GrReferenceExpression)expression).resolve();
      if (resolved instanceof PsiClass) {
        return TypesUtil.createType((PsiClass)resolved);
      }
    }

    if (expression instanceof GrBuiltinTypeClassExpression) {
      return ((GrBuiltinTypeClassExpression)expression).getPrimitiveType();
    }

    if (expression instanceof GrIndexProperty) {
      if (((GrIndexProperty)expression).getArgumentList().getAllArguments().length != 0) return null;
      PsiType arrayTypeBase = getClassReferenceFromExpression(((GrIndexProperty)expression).getInvokedExpression());
      return arrayTypeBase == null ? null : arrayTypeBase.createArrayType();
    }
    return null;
  }

  @Nullable
  public static PsiType unwrapClassType(@Nullable PsiType type) {
    if (!(type instanceof PsiClassType)) return null;

    PsiClass psiClass = ((PsiClassType)type).resolve();
    if (psiClass == null || !CommonClassNames.JAVA_LANG_CLASS.equals(psiClass.getQualifiedName())) return null;

    final PsiType[] params = ((PsiClassType)type).getParameters();
    if (params.length != 1) return null;

    return params[0];
  }

  public static boolean isAccessible(@NotNull PsiElement place, @NotNull PsiNamedElement namedElement) {
    if (namedElement instanceof GrField) {
      final GrField field = (GrField)namedElement;
      if (org.jetbrains.plugins.groovy.lang.psi.util.PsiUtil.isAccessible(place, field)) {
        return true;
      }

      for (GrAccessorMethod method : field.getGetters()) {
        if (org.jetbrains.plugins.groovy.lang.psi.util.PsiUtil.isAccessible(place, method)) {
          return true;
        }
      }
      final GrAccessorMethod setter = field.getSetter();
      if (setter != null && org.jetbrains.plugins.groovy.lang.psi.util.PsiUtil.isAccessible(place, setter)) {
        return true;
      }

      return false;
    }
    return !(namedElement instanceof PsiMember) || org.jetbrains.plugins.groovy.lang.psi.util.PsiUtil.isAccessible(place, ((PsiMember)namedElement));
  }

  public static boolean isStaticsOK(@NotNull PsiElement place,
                                    @NotNull PsiNamedElement element,
                                    @Nullable PsiElement resolveContext,
                                    boolean filterStaticAfterInstanceQualifier) {
    if (resolveContext instanceof GrImportStatement) return true;

    if (element instanceof PsiModifierListOwner) {
      return GrStaticChecker.isStaticsOK((PsiModifierListOwner)element, place, resolveContext, filterStaticAfterInstanceQualifier);
    }
    return true;
  }

  public static boolean canResolveToMethod(@NotNull GrReferenceExpression ref) {
    assert !ref.hasMemberPointer();
    return ref.getParent() instanceof GrMethodCall;
  }

  public static boolean processClassDeclarations(@NotNull PsiClass scope,
                                                   @NotNull PsiScopeProcessor processor,
                                                   @NotNull ResolveState state,
                                                   @Nullable PsiElement lastParent, @NotNull PsiElement place) {
    for (PsiScopeProcessor each : MultiProcessor.allProcessors(processor)) {
      if (!scope.processDeclarations(each, state, lastParent, place)) return false;
    }
    return true;
  }
}
