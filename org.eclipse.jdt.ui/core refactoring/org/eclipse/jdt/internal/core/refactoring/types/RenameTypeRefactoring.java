/*
 * (c) Copyright IBM Corp. 2000, 2001.
 * All Rights Reserved.
 */
package org.eclipse.jdt.internal.core.refactoring.types;

import java.util.Iterator;
import java.util.List;

import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.SubProgressMonitor;

import org.eclipse.jdt.core.Flags;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IImportDeclaration;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.internal.core.refactoring.base.IChange;
import org.eclipse.jdt.internal.core.refactoring.base.RefactoringStatus;
import org.eclipse.jdt.internal.core.refactoring.tagging.IPreactivatedRefactoring;
import org.eclipse.jdt.internal.core.refactoring.tagging.IRenameRefactoring;
import org.eclipse.jdt.internal.core.refactoring.text.ITextBuffer;
import org.eclipse.jdt.internal.core.refactoring.text.ITextBufferChange;
import org.eclipse.jdt.internal.core.refactoring.text.ITextBufferChangeCreator;
import org.eclipse.jdt.internal.core.refactoring.text.SimpleReplaceTextChange;
import org.eclipse.jdt.internal.core.refactoring.text.SimpleTextChange;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.IJavaSearchScope;
import org.eclipse.jdt.core.search.ISearchPattern;
import org.eclipse.jdt.core.search.SearchEngine;

import org.eclipse.jdt.internal.compiler.AbstractSyntaxTreeVisitorAdapter;
import org.eclipse.jdt.internal.compiler.ast.TypeDeclaration;
import org.eclipse.jdt.internal.compiler.lookup.BlockScope;
import org.eclipse.jdt.internal.compiler.lookup.ClassScope;
import org.eclipse.jdt.internal.compiler.lookup.Scope;
import org.eclipse.jdt.internal.core.CompilationUnit;
import org.eclipse.jdt.internal.core.refactoring.Assert;
import org.eclipse.jdt.internal.core.refactoring.Checks;
import org.eclipse.jdt.internal.core.refactoring.CompositeChange;
import org.eclipse.jdt.internal.core.refactoring.JavaModelUtility;
import org.eclipse.jdt.internal.core.refactoring.RefactoringSearchEngine;
import org.eclipse.jdt.internal.core.refactoring.RenameResourceChange;
import org.eclipse.jdt.internal.core.refactoring.SearchResult;
import org.eclipse.jdt.internal.core.refactoring.RefactoringCoreMessages;

/**
 * <p>
 * <bf>NOTE:<bf> This class/interface is part of an interim API that is still under development 
 * and expected to change significantly before reaching stability. It is being made available at 
 * this early stage to solicit feedback from pioneering adopters on the understanding that any 
 * code that uses this API will almost certainly be broken (repeatedly) as the API evolves.</p>
 */
public class RenameTypeRefactoring extends TypeRefactoring implements IRenameRefactoring, IPreactivatedRefactoring{

	private String fNewName;
	private List fOccurrences;
	private ITextBufferChangeCreator fTextBufferChangeCreator;
	
	public RenameTypeRefactoring(ITextBufferChangeCreator changeCreator, IJavaSearchScope scope, IType type, String newName){
		super(scope, type);
		Assert.isNotNull(newName, "new name"); //$NON-NLS-1$
		Assert.isNotNull(changeCreator, "change creator"); //$NON-NLS-1$
		fNewName= newName;
		fTextBufferChangeCreator= changeCreator;
	}
	
	public RenameTypeRefactoring(ITextBufferChangeCreator changeCreator, IType type) {
		super(type);
		Assert.isNotNull(changeCreator, "change creator"); //$NON-NLS-1$
		fTextBufferChangeCreator= changeCreator;
	}
	
	public final String getNewName(){
		return fNewName;
	}

	/**
	 * @see IRenameRefactoring#setNewName
	 */
	public final void setNewName(String newName){
		Assert.isNotNull(newName);
		fNewName= newName;
	}
	
	/**
	 * @see IRenameRefactoring#getCurrentName
	 */
	public final String getCurrentName(){
		return getType().getElementName();
	}
	
	/**
	 * @see IRefactoring#getName
	 */
	public String getName(){
		return RefactoringCoreMessages.getFormattedString("RenameTypeRefactoring.name",  //$NON-NLS-1$
														new String[]{getType().getFullyQualifiedName(), fNewName});
	}

	//------------- Conditions -----------------
		
	public RefactoringStatus checkInput(IProgressMonitor pm) throws JavaModelException{
		Assert.isNotNull(getType(), "type"); //$NON-NLS-1$
		Assert.isNotNull(fNewName, "newName"); //$NON-NLS-1$
		RefactoringStatus result= new RefactoringStatus();
		try{
			pm.beginTask("", 73); //$NON-NLS-1$
			pm.subTask(RefactoringCoreMessages.getString("RenameTypeRefactoring.checking")); //$NON-NLS-1$
			result.merge(checkNewName());
			if (result.hasFatalError())
				return result;
			result.merge(Checks.checkIfCuBroken(getType()));
			if (result.hasFatalError())
				return result;
			
			pm.worked(2);
			result.merge(checkTypesInCompilationUnit());
			pm.worked(1);
			result.merge(checkImportedTypes());	
			pm.worked(1);
			if (mustRenameCU())
				result.merge(Checks.checkCompilationUnitNewName(getType().getCompilationUnit(), fNewName));
			pm.worked(1);	
			if (isEnclosedInType(getType(), fNewName))	
				result.addError(RefactoringCoreMessages.getFormattedString("RenameTypeRefactoring.enclosed",  //$NON-NLS-1$
																			new String[]{getType().getFullyQualifiedName(), fNewName}));
			pm.worked(1);	
			if (enclosesType(getType(), fNewName))
				result.addError(RefactoringCoreMessages.getFormattedString("RenameTypeRefactoring.encloses",  //$NON-NLS-1$
																			new String[]{getType().getFullyQualifiedName(), fNewName}));
			pm.worked(1);	
			if (typeNameExistsInPackage(getType().getPackageFragment(), fNewName))
				result.addError(RefactoringCoreMessages.getFormattedString("RenameTypeRefactoring.exists", //$NON-NLS-1$
																			new String[]{fNewName, getType().getPackageFragment().getElementName()}));
			pm.worked(1);	
			if (compilationUnitImportsType(getType().getCompilationUnit(), fNewName))	
				result.addError(RefactoringCoreMessages.getFormattedString("RenameTypeRefactoring.imported", //$NON-NLS-1$
																			new Object[]{fNewName, getResource(getType()).getFullPath()}));
			pm.worked(1);	
			result.merge(Checks.checkForNativeMethods(getType()));
			pm.worked(1);	
			result.merge(Checks.checkForMainMethod(getType()));
			pm.worked(1);	
			
			// before doing any expensive analysis
			if (result.hasFatalError())
				return result;
				
			result.merge(analyseEnclosedLocalTypes(getType(), fNewName));
			pm.worked(1);

			// before doing _the really_ expensive analysis
			if (result.hasFatalError())
				return result;
									
			//if (Flags.isPublic(getType().getFlags()))
			//	result.merge(analyzeImportDeclarations(new SubProgressMonitor(pm, 8)));
				
			pm.worked(1);
			result.merge(Checks.checkAffectedResourcesAvailability(getOccurrences(new SubProgressMonitor(pm, 35))));
			result.merge(analyzeAffectedCompilationUnits(new SubProgressMonitor(pm, 25)));
		} finally {
			pm.done();
		}	
		return result;
	}
	
	private List getOccurrences(IProgressMonitor pm) throws JavaModelException{
		pm.subTask(RefactoringCoreMessages.getString("RenameTypeRefactoring.searching"));	 //$NON-NLS-1$
		fOccurrences= RefactoringSearchEngine.search(pm, getScope(), createSearchPattern());
		return fOccurrences;
	}
	public RefactoringStatus checkPreactivation() throws JavaModelException{
		RefactoringStatus result= new RefactoringStatus();
		result.merge(checkAvailability(getType()));
		if (!Checks.isTopLevel(getType()))
			result.addFatalError(RefactoringCoreMessages.getString("RenameTypeRefactoring.only_toplevel")); //$NON-NLS-1$
		if (isSpecialCase(getType()))
			result.addFatalError(RefactoringCoreMessages.getString("RenameTypeRefactoring.special_case"));	 //$NON-NLS-1$
		return result;
	}
	
	public RefactoringStatus checkActivation(IProgressMonitor pm) throws JavaModelException{
		return Checks.checkIfCuBroken(getType());
	}
		
	public RefactoringStatus checkNewName(){
		Assert.isNotNull(getType(), "type"); //$NON-NLS-1$
		Assert.isNotNull(fNewName, "new name"); //$NON-NLS-1$
		RefactoringStatus result= new RefactoringStatus();
		result.merge(Checks.checkTypeName(fNewName));
		if (Checks.isAlreadyNamed(getType(), fNewName))
			result.addFatalError(RefactoringCoreMessages.getString("RenameTypeRefactoring.choose_another_name"));	 //$NON-NLS-1$
		return result;
	}
	
	private RefactoringStatus checkImportedTypes() throws JavaModelException{
		IImportDeclaration[] imports= getType().getCompilationUnit().getImports();
		if (imports == null)
			return null;
		RefactoringStatus result= new RefactoringStatus();
		for (int i= 0; i < imports.length; i++)
			analyzeImportDeclaration(imports[i], result);
		return result;
	}
	
	private RefactoringStatus checkTypesInCompilationUnit(){
		RefactoringStatus result= new RefactoringStatus();
		if (Checks.isTopLevel(getType())){
			ICompilationUnit cu= (ICompilationUnit)getType().getParent();
			/*
			 * ICompilationUnit.getType expects simple name
			 */ 
			if ((fNewName.indexOf(".") == -1) && cu.getType(fNewName).exists()) //$NON-NLS-1$
				result.addError(RefactoringCoreMessages.getFormattedString("RenameTypeRefactoring.type_exists_in_cu", //$NON-NLS-1$
																		new String[]{fNewName, cu.getElementName()}));
		} else {
			if (getType().getDeclaringType().getType(fNewName).exists())
				result.addError(RefactoringCoreMessages.getFormattedString("RenameTypeRefactoring.member_type_exists", //$NON-NLS-1$
																		new String[]{fNewName, getType().getDeclaringType().getFullyQualifiedName()}));
		}
		return result;
	}
	
	/*
	 * Checks if the specified type has locally defined types with the specified names
	 * and if it declares a native method
	 */ 	
	private static RefactoringStatus analyseEnclosedLocalTypes(final IType type, final String newName) throws JavaModelException{
		CompilationUnit cu= (CompilationUnit) (JavaCore.create(getResource(type)));
		final RefactoringStatus result= new RefactoringStatus();
		cu.accept(new AbstractSyntaxTreeVisitorAdapter(){
			public boolean visit(TypeDeclaration typeDeclaration, ClassScope scope) {
				if ( new String(typeDeclaration.name).equals(newName)
					&& isInType(type.getElementName(), scope))
						result.addError(RefactoringCoreMessages.getFormattedString("RenameTypeRefactoring.local_type_name", //$NON-NLS-1$
																				new String[]{type.getElementName(), newName}));
				if (typeDeclaration.methods != null){
					for (int i=0; i < typeDeclaration.methods.length; i++){
						if (typeDeclaration.methods[i].isNative())
							result.addWarning(RefactoringCoreMessages.getFormattedString("RenameTypeRefactoring.local_type_native",  //$NON-NLS-1$
																				new String[]{type.getElementName(), "UnsatisfiedLinkError"})); //$NON-NLS-1$
					}	
				}	
				return true;
			}
			
			public boolean visit(TypeDeclaration typeDeclaration, BlockScope scope) {
				if (new String(typeDeclaration.name).equals(newName)
				    && isInType(type.getElementName(), scope))
						result.addError(RefactoringCoreMessages.getFormattedString("RenameTypeRefactoring.enclosed_type_name", //$NON-NLS-1$
																				new String[]{type.getElementName(), newName}));
						
				if (typeDeclaration.methods != null){
					for (int i=0; i < typeDeclaration.methods.length; i++){
						if (typeDeclaration.methods[i].isNative())
							result.addWarning(RefactoringCoreMessages.getFormattedString("RenameTypeRefactoring.enclosed_type_native",  //$NON-NLS-1$
																				new String[]{type.getElementName(), "UnsatisfiedLinkError"})); //$NON-NLS-1$
							
					}	
				}	
				return true;
			}
		});
		return result;
	}

	private boolean mustRenameCU() throws JavaModelException{
		return Checks.isTopLevel(getType())	&& (Flags.isPublic(getType().getFlags()));
	}
	
	//-----	analyzing import declarations in other compilation units
	
	/* (non java-doc)
	 * checks if any of the SearchResults on the list is an <code>IImportDeclaration</code> on demand
	 */
	private static IImportDeclaration findImportOnDemand(List searchResults){
		for (Iterator iter= searchResults.iterator(); iter.hasNext(); ){
			SearchResult searchResult= (SearchResult)iter.next();
			IJavaElement element= searchResult.getEnclosingElement();
			if ((element instanceof IImportDeclaration)
			 	 && ((IImportDeclaration)element).isOnDemand()){
				return (IImportDeclaration)element;
			}	
		}
		return null;
	}
	
	private static ICompilationUnit getCompilationUnit(IImportDeclaration imp){
		return (ICompilationUnit)imp.getParent().getParent();
	}
	
	private String getSimpleName(IImportDeclaration imp){
		String name= imp.getElementName();
		if (imp.isOnDemand())
			return name;
		else
			return name.substring(0, name.length() - 2); //remove the '.*' suffix
	}
	
	private void analyzeImportedTypes(IType[] types, RefactoringStatus result, IImportDeclaration imp) throws JavaModelException{
		if (types == null)
			return;
		for (int i= 0; i < types.length; i++) {
			//could this be a problem (same package imports)?
			if (Flags.isPublic(types[i].getFlags()) && types[i].getElementName().equals(fNewName)){
				result.addError(RefactoringCoreMessages.getFormattedString("RenameTypeRefactoring.name_conflict1", //$NON-NLS-1$
																			new Object[]{types[i].getFullyQualifiedName(), getFullPath(getCompilationUnit(imp))}));
			}
		}
	}
	
	private void analyzeImportDeclaration(IImportDeclaration imp, RefactoringStatus result) throws JavaModelException{
		String name= getSimpleName(imp);
		
		if (!imp.isOnDemand()){
			IType importedType= (IType)JavaModelUtility.convertFromImportDeclaration(imp);
			if (name.substring(name.lastIndexOf(".") + 1).equals(fNewName)){ //$NON-NLS-1$
				if (importedType != null)
					result.addError(RefactoringCoreMessages.getFormattedString("RenameTypeRefactoring.name_conflict1", //$NON-NLS-1$
																			new Object[]{importedType.getFullyQualifiedName(), getFullPath(getCompilationUnit(imp))}));
				else
					result.addError(RefactoringCoreMessages.getFormattedString("RenameTypeRefactoring.name_conflict1", //$NON-NLS-1$
																			new Object[]{name, getFullPath(getCompilationUnit(imp))}));
					
			}	
			return;
		}
		
		IJavaElement imported= JavaModelUtility.convertFromImportDeclaration(imp);
		if (imported == null)
			return;
			
		if (imported instanceof IPackageFragment){
			ICompilationUnit[] cus= ((IPackageFragment)imported).getCompilationUnits();
			if (cus != null){
				for (int i= 0; i < cus.length; i++) {
					analyzeImportedTypes(cus[i].getTypes(), result, imp);
				}	
			}
		} else {
			//cast safe: see JavaModelUtility.convertFromImportDeclaration
			analyzeImportedTypes(((IType)imported).getTypes(), result, imp);
		}
	}
	
	private RefactoringStatus analyzeImportDeclarations(IProgressMonitor pm) throws JavaModelException{
		IPackageFragment thisPackage= getType().getPackageFragment();
		if (thisPackage.isDefaultPackage())
			return null;
		RefactoringStatus result= new RefactoringStatus();	
		ISearchPattern pattern= SearchEngine.createSearchPattern(thisPackage, IJavaSearchConstants.REFERENCES);
		List grouped= RefactoringSearchEngine.search(pm, getScope(), pattern);
		
		for (Iterator iter= grouped.iterator(); iter.hasNext();){
			IImportDeclaration declaration= findImportOnDemand((List)iter.next());
			if (declaration == null)
				continue;
			IImportDeclaration[] imports= getCompilationUnit(declaration).getImports();
			if (imports == null)
				continue;
			for (int i= 0; i < imports.length; i++){
				analyzeImportDeclaration(imports[i], result);
			}
		}
		return result;
	}
	
	//-------------- AST visitor-based analysis
	
	/*
	 * (non java-doc)
	 * Analyzes all compilation units in which type is referenced
	 */
	private RefactoringStatus analyzeAffectedCompilationUnits(IProgressMonitor pm) throws JavaModelException{
		RefactoringStatus result= Checks.excludeCompilationUnits(fOccurrences, getUnsavedFileList());
		if (result.hasFatalError())
			return result;
		
		Iterator iter= fOccurrences.iterator();
		pm.beginTask("", fOccurrences.size()); //$NON-NLS-1$
		RenameTypeASTAnalyzer analyzer= new RenameTypeASTAnalyzer(fNewName, getType());
		while (iter.hasNext()){
			analyzeCompilationUnit(pm, analyzer, (List)iter.next(), result);
			pm.worked(1);
		}
		return result;
	}
	
	private void analyzeCompilationUnit(IProgressMonitor pm, RenameTypeASTAnalyzer analyzer, List searchResults, RefactoringStatus result)  throws JavaModelException {
		SearchResult searchResult= (SearchResult)searchResults.get(0);
		CompilationUnit cu= (CompilationUnit) (JavaCore.create(searchResult.getResource()));
		pm.subTask(RefactoringCoreMessages.getFormattedString("RenameTypeRefactoring.analyzing", cu.getElementName())); //$NON-NLS-1$
		if ((! cu.exists()) || (cu.isReadOnly()) || (!cu.isStructureKnown()))
			return;
		result.merge(analyzeCompilationUnit(cu));	
		result.merge(analyzer.analyze(searchResults, cu));
	}
	
	/* non java-doc
	 * all the analysis that can be done with no AST walking
	 */
	private RefactoringStatus analyzeCompilationUnit(ICompilationUnit cu) throws JavaModelException{
		RefactoringStatus result= new RefactoringStatus();
		/*
		 * ICompilationUnit.getType feature - walkback if not simple name
		 */ 
		String name= fNewName;
		if (fNewName.indexOf(".") != -1) //$NON-NLS-1$
			name= fNewName.substring(0, fNewName.indexOf(".")); //$NON-NLS-1$
		if (cu.getType(name).exists())
			result.addError(RefactoringCoreMessages.getFormattedString("RenameTypeRefactoring.name_conflict2", //$NON-NLS-1$
																		new Object[]{fNewName, getResource(cu).getFullPath()}));
		return result;
	}
	
	//------------- Changes ---------------
	
	private boolean willRenameCU() throws JavaModelException{
		if (mustRenameCU())
			return true;
		if (! Checks.isTopLevel(getType()))
			return false;
		if (! getType().getCompilationUnit().getElementName().equals(getType().getElementName() + ".java")) //$NON-NLS-1$
			return false;
		return Checks.checkCompilationUnitNewName(getType().getCompilationUnit(), fNewName) == null;
	}
	
	public IChange createChange(IProgressMonitor pm) throws JavaModelException{
		CompositeChange builder= new CompositeChange();
		pm.beginTask(RefactoringCoreMessages.getString("RenameTypeRefactoring.creating_change"), fOccurrences.size() + 1); //$NON-NLS-1$
		addOccurrences(pm, builder);
		if (willRenameCU())
			builder.addChange(new RenameResourceChange(getResource(getType()), fNewName));
		pm.worked(1);	
		pm.done();	
		return builder;	
	}
	
	private void addConstructorRenames(ITextBufferChange change) throws JavaModelException{
		IMethod[] methods= getType().getMethods();
		int typeNameLength= getType().getElementName().length();
		for (int i= 0; i < methods.length; i++){
			if (methods[i].isConstructor()) {
				/*
				 * constructor declarations cannot be fully qualified so we can use simple replace here
				 *
				 * if (methods[i].getNameRange() == null), then it's a binary file so it's wrong anyway 
				 * (checked as a precondition)
				 */
				change.addReplace(RefactoringCoreMessages.getString("RenameTypeRefactoring.rename_constructor"), methods[i].getNameRange().getOffset(), typeNameLength, fNewName); //$NON-NLS-1$
			}
		}	
	}
	
	private void addOccurrences(IProgressMonitor pm, CompositeChange builder) throws JavaModelException{
		IResource ourResource= getResource(getType());
		for (Iterator iter= fOccurrences.iterator(); iter.hasNext();){
			List l= (List)iter.next();
			IResource resource= ((SearchResult)l.get(0)).getResource();
			ICompilationUnit cu= (ICompilationUnit)JavaCore.create(resource);
			ITextBufferChange change= fTextBufferChangeCreator.create(RefactoringCoreMessages.getString("RenameTypeRefactoring.update_references_to") + getType().getFullyQualifiedName(), cu ); //$NON-NLS-1$
			for (Iterator subIter= l.iterator(); subIter.hasNext();){
				change.addSimpleTextChange(createTextChange((SearchResult)subIter.next()));
			}
			if (resource.equals(ourResource)){
				/* there is at least one Occurrence in the compilation unit that declares our type
				 * (the type delaration itself) so this code is always executed exactly once
				 */
				addConstructorRenames(change);
			}	
			builder.addChange(change);
			pm.worked(1);
		}
		fOccurrences= null; //to prevent memory leak
	}
	
	private SimpleReplaceTextChange createTextChange(SearchResult searchResult) {
		SimpleReplaceTextChange change= new SimpleReplaceTextChange(RefactoringCoreMessages.getString("RenameTypeRefactoring.update_reference"), searchResult.getStart(), searchResult.getEnd() - searchResult.getStart(), fNewName) { //$NON-NLS-1$
			protected SimpleTextChange[] adjust(ITextBuffer buffer) {
				String packageName= getType().getPackageFragment().getElementName();
				String oldTypeName= getType().getElementName();
				String oldText= buffer.getContent(getOffset(), getLength());
				if (!oldTypeName.equals(packageName) 
				  && oldText.startsWith(packageName) 
				  && (! getType().getPackageFragment().isDefaultPackage())
				  && (! getText().startsWith(packageName))){
					setLength(oldText.indexOf(oldTypeName) + oldTypeName.length());
					oldText= oldText.substring(0, getLength());
					setText(packageName + oldText.substring(packageName.length(), oldText.indexOf(oldTypeName)) + getText());
				}	
				return null;
			}
		};
		return change;
	}

		
	private ISearchPattern createSearchPattern() throws JavaModelException{
		return SearchEngine.createSearchPattern(getType(), IJavaSearchConstants.ALL_OCCURRENCES);
	}
	
	static String getFullPath(ICompilationUnit cu){
		/*
		 * we catch the exception mainly in order to avoid dealing with it in the ast visitor
		 */
		Assert.isTrue(cu.exists());
		IPath path= null;
		try {
			return getResource(cu).getFullPath().toString();
		} catch (JavaModelException e){
			return cu.getElementName();
		}
	}
	
	private static boolean isInType(String typeName, Scope scope){
		Scope current= scope;
		while (current != null){
			if (current instanceof ClassScope){
				if (typeName.equals(new String(((ClassScope)current).referenceContext.name)))
					return true;
			}
			current= current.parent;
		}
		return false;
	}
					
	private static boolean isSpecialCase(IType type) throws JavaModelException{
		return type.getPackageFragment().getElementName().equals("java.lang");	 //$NON-NLS-1$
	}
	
	private static boolean isEnclosedInType(IType type, String newName) {
		IType enclosing= type.getDeclaringType();
		while (enclosing != null){
			if (newName.equals(enclosing.getElementName()))
				return true;
			else 
				enclosing= enclosing.getDeclaringType();	
		}
		return false;
	}
	
	private static boolean enclosesType(IType type, String newName) throws JavaModelException{
		IType[] enclosedTypes= type.getTypes();
		for (int i= 0; i < enclosedTypes.length; i++){
			if (newName.equals(enclosedTypes[i].getElementName()) || enclosesType(enclosedTypes[i], newName))
				return true;
		}
		return false;
	}
	
	private static boolean compilationUnitImportsType(ICompilationUnit cu, String typeName) throws JavaModelException{
		IImportDeclaration[] imports= cu.getImports();
		String dotTypeName= "." + typeName; //$NON-NLS-1$
		for (int i= 0; i < imports.length; i++){
			if (imports[i].getElementName().endsWith(dotTypeName))
				return true;
		}
		return false;
	}
	
}
