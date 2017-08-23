/*******************************************************************************
 * Copyright (c) 2017 Till Brychcy and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Till Brychcy - initial API and implementation
 *******************************************************************************/
package org.eclipse.jdt.internal.ui.filters;


import org.eclipse.jface.viewers.Viewer;
import org.eclipse.jface.viewers.ViewerFilter;

import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.JavaModelException;


public class TestCodeFilter extends ViewerFilter {
	private static IClasspathEntry determineClassPathEntry(Object element) {
		if (element instanceof IJavaElement) {
			IPackageFragmentRoot packageFragmentRoot= (IPackageFragmentRoot) ((IJavaElement) element).getAncestor(IJavaElement.PACKAGE_FRAGMENT_ROOT);
			if (packageFragmentRoot != null) {
				try {
					return packageFragmentRoot.getResolvedClasspathEntry();
				} catch (JavaModelException e) {
					return null;
				}
			}
		}
		return null;
	}

	@Override
	public boolean select(Viewer viewer, Object parent, Object element) {
		IClasspathEntry classpathEntry= determineClassPathEntry(element);
		if(classpathEntry != null && classpathEntry.isTest()) {
			return false;
		}
		return true;
	}
}
