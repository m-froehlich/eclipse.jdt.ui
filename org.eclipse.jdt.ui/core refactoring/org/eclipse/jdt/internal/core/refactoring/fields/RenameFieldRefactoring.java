/*
 * (c) Copyright IBM Corp. 2000, 2001.
 * All Rights Reserved.
 */
package org.eclipse.jdt.internal.core.refactoring.fields;
import java.util.Iterator;
import java.util.List;

import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.SubProgressMonitor;

import org.eclipse.jdt.core.Flags;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IField;
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

import org.eclipse.jdt.internal.core.CompilationUnit;
import org.eclipse.jdt.internal.core.refactoring.Assert;
import org.eclipse.jdt.internal.core.refactoring.Checks;
import org.eclipse.jdt.internal.core.refactoring.CompositeChange;
import org.eclipse.jdt.internal.core.refactoring.RefactoringSearchEngine;
import org.eclipse.jdt.internal.core.refactoring.SearchResult;
import org.eclipse.jdt.internal.core.refactoring.RefactoringCoreMessages;

/**
 * <p>
 * <bf>NOTE:<bf> This class/interface is part of an interim API that is still under development 
 * and expected to change significantly before reaching stability. It is being made available at 
 * this early stage to solicit feedback from pioneering adopters on the understanding that any 
 * code that uses this API will almost certainly be broken (repeatedly) as the API evolves.</p>
 */
public class RenameFieldRefactoring extends FieldRefactoring implements IRenameRefactoring, IPreactivatedRefactoring{
	
	private String fNewName;
	
	private List fOccurrences;
	private ITextBufferChangeCreator fTextBufferChangeCreator;

	public RenameFieldRefactoring(ITextBufferChangeCreator changeCreator, IField field){
		super(field);
		Assert.isNotNull(changeCreator, "change creator"); //$NON-NLS-1$
		fTextBufferChangeCreator= changeCreator;
		correctScope();
	}
	
	public RenameFieldRefactoring(ITextBufferChangeCreator changeCreator, IJavaSearchScope scope, IField field, String newName){
		super(scope, field);
		Assert.isNotNull(changeCreator, "change creator"); //$NON-NLS-1$
		Assert.isNotNull(newName, "new name"); //$NON-NLS-1$
		fTextBufferChangeCreator= changeCreator;
		fNewName= newName;
		correctScope();
	}
	
	/* non java-doc
	 * narrow down the scope
	 */ 
	private void correctScope(){
		if (getField().isBinary())
			return;
		try{
			//only the declaring compilation unit
			if (Flags.isPrivate(getField().getFlags()))
				setScope(SearchEngine.createJavaSearchScope(new IResource[]{getResource(getField())}));
		} catch (JavaModelException e){
			//do nothing
		}
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
		return getField().getElementName();
	}
		
	public final String getNewName(){
		return fNewName;
	}
	
	protected final ITextBufferChangeCreator getChangeCreator(){
		return fTextBufferChangeCreator;
	}
	
	/**
	 * @see IRefactoring#getName
	 */
	 public String getName(){
	 	return RefactoringCoreMessages.getFormattedString("RenameFieldRefactoring.name", //$NON-NLS-1$
	 													 new String[]{getField().getElementName(), getNewName()});
	 }
	
	// -------------- Preconditions -----------------------
	
	public RefactoringStatus checkPreactivation() throws JavaModelException {
		RefactoringStatus result= new RefactoringStatus();
		result.merge(checkAvailability(getField()));	
		return result;
	}
	
	public RefactoringStatus checkActivation(IProgressMonitor pm) throws JavaModelException{
		return Checks.checkIfCuBroken(getField());
	}
	
	/**
	 * @see IRenameRefactoring#checkNewName
	 */
	public RefactoringStatus checkNewName() {
		RefactoringStatus result= new RefactoringStatus();
		
		result.merge(Checks.checkFieldName(getNewName()));
			
		if (Checks.isAlreadyNamed(getField(), getNewName()))
			result.addFatalError(RefactoringCoreMessages.getString("RenameFieldRefactoring.another_name")); //$NON-NLS-1$
		if (getField().getDeclaringType().getField(getNewName()).exists())
			result.addError(RefactoringCoreMessages.getString("RenameFieldRefactoring.field_already_defined")); //$NON-NLS-1$
		return result;
	}
	
	/**
	 * @see Refactoring#checkInput
	 */
	public RefactoringStatus checkInput(IProgressMonitor pm) throws JavaModelException {
		pm.beginTask("", 9); //$NON-NLS-1$
		pm.subTask(RefactoringCoreMessages.getString("RenameFieldRefactoring.checking")); //$NON-NLS-1$
		RefactoringStatus result= new RefactoringStatus();
		result.merge(Checks.checkIfCuBroken(getField()));
		if (result.hasFatalError())
			return result;
		result.merge(checkNewName());
		pm.worked(1);
		result.merge(checkEnclosingHierarchy());
		pm.worked(1);
		result.merge(checkNestedHierarchy(getField().getDeclaringType()));
		pm.worked(1);
		result.merge(Checks.checkAffectedResourcesAvailability(getOccurrences(new SubProgressMonitor(pm, 3))));
		result.merge(analyzeAffectedCompilationUnits(new SubProgressMonitor(pm, 3)));
		pm.done();
		return result;
	}
	
	
	private RefactoringStatus checkNestedHierarchy(IType type) throws JavaModelException {
		IType[] nestedTypes= type.getTypes();
		if (nestedTypes == null)
			return null;
		RefactoringStatus result= new RefactoringStatus();	
		for (int i= 0; i < nestedTypes.length; i++){
			IField otherField= nestedTypes[i].getField(getNewName());
			if (otherField.exists())
				result.addWarning(RefactoringCoreMessages.getFormattedString("RenameFieldRefactoring.hiding", //$NON-NLS-1$
																			new String[]{getField().getElementName(), getNewName(), nestedTypes[i].getFullyQualifiedName()}));
			result.merge(checkNestedHierarchy(nestedTypes[i]));	
		}	
		return result;
	}
	
	private RefactoringStatus checkEnclosingHierarchy() throws JavaModelException {
		IType current= getField().getDeclaringType();
		if (Checks.isTopLevel(current))
			return null;
		RefactoringStatus result= new RefactoringStatus();
		while (current != null){
			IField otherField= current.getField(getNewName());
			if (otherField.exists())
				result.addWarning(RefactoringCoreMessages.getFormattedString("RenameFieldRefactoring.hiding2", //$NON-NLS-1$
				 															new String[]{getNewName(), current.getFullyQualifiedName(), getField().getElementName()}));
			current= current.getDeclaringType();
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
			
		pm.beginTask("", fOccurrences.size()); //$NON-NLS-1$
		Iterator iter= fOccurrences.iterator();
		RenameFieldASTAnalyzer analyzer= new RenameFieldASTAnalyzer(fNewName, getField());
		while (iter.hasNext()){
			analyzeCompilationUnit(pm, analyzer, (List)iter.next(), result);
		}
		return result;
	}
	
	private void analyzeCompilationUnit(IProgressMonitor pm, RenameFieldASTAnalyzer analyzer, List searchResults, RefactoringStatus result)  throws JavaModelException {
		SearchResult searchResult= (SearchResult)searchResults.get(0);
		CompilationUnit cu= (CompilationUnit) (JavaCore.create(searchResult.getResource()));
		pm.subTask(RefactoringCoreMessages.getFormattedString("RenameFieldRefactoring.analyzing", cu.getElementName())); //$NON-NLS-1$
		if ((! cu.exists()) || (cu.isReadOnly()) || (!cu.isStructureKnown()))
			return;
		result.merge(analyzer.analyze(searchResults, cu));
	}
	
	// ---------- Changes -----------------
	/**
	 * @see IRefactoring#createChange
	 */
	public IChange createChange(IProgressMonitor pm) throws JavaModelException {
		pm.beginTask(RefactoringCoreMessages.getString("RenameFieldRefactoring.creating_change"), 7); //$NON-NLS-1$
		CompositeChange builder= new CompositeChange();
		getOccurrences(new SubProgressMonitor(pm, 6));
		addOccurrences(pm, builder);
		pm.worked(1);
		pm.done();
		return builder; 
	}
	
	private SimpleReplaceTextChange createTextChange(SearchResult searchResult) {
		return new SimpleReplaceTextChange(RefactoringCoreMessages.getString("RenameFieldRefactoring.update_reference"), searchResult.getStart(), searchResult.getEnd() - searchResult.getStart(), getNewName()){ //$NON-NLS-1$
			protected SimpleTextChange[] adjust(ITextBuffer buffer) {
				String oldText= buffer.getContent(getOffset(), getLength());
				if (oldText.startsWith("this.") && (! getText().startsWith("this."))){ //$NON-NLS-2$ //$NON-NLS-1$
					setText("this." + getText()); //$NON-NLS-1$
					setLength(getLength());
				}
				return null;
			}
		};
	}	

	private void addOccurrences(IProgressMonitor pm, CompositeChange builder) throws JavaModelException{
		List grouped= getOccurrences(null);
		for (Iterator iter= grouped.iterator(); iter.hasNext();){
			List l= (List)iter.next();
			ITextBufferChange change= getChangeCreator().create(RefactoringCoreMessages.getString("RenameFieldRefactoring.update_references_to") + getField().getElementName(), (ICompilationUnit)JavaCore.create(((SearchResult)l.get(0)).getResource())); //$NON-NLS-1$
			for (Iterator subIter= l.iterator(); subIter.hasNext();){
				change.addSimpleTextChange(createTextChange((SearchResult)subIter.next()));
			}
			builder.addChange(change);
			pm.worked(1);
		}
		setOccurrences(null); //to prevent memory leak
	}
	
	//--------------------------------------
	
	protected ISearchPattern createSearchPattern(){
		return SearchEngine.createSearchPattern(getField(), IJavaSearchConstants.ALL_OCCURRENCES);
	}
	
	protected List getOccurrences(IProgressMonitor pm) throws JavaModelException{
		if (fOccurrences == null){
			if (pm == null)
				pm= new NullProgressMonitor();
			pm.subTask(RefactoringCoreMessages.getString("RenameFieldRefactoring.searching"));	 //$NON-NLS-1$
			fOccurrences= RefactoringSearchEngine.search(new SubProgressMonitor(pm, 6), getScope(), createSearchPattern());
		}	
		return fOccurrences;
	}
	
	/*package*/ final void setOccurrences(List Occurrences){
		fOccurrences= Occurrences;
	}
}