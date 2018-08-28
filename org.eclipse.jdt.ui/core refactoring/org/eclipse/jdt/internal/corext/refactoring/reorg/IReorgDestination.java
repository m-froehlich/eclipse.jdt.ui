/*******************************************************************************
 * Copyright (c) 2007, 2008 IBM Corporation and others.
 *
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.jdt.internal.corext.refactoring.reorg;

import org.eclipse.jdt.internal.ui.dnd.JdtViewerDropAdapter;

/**
 * {@link ReorgDestinationFactory} can create concrete
 * instances
 */
public interface IReorgDestination {

    public static final int LOCATION_BEFORE = JdtViewerDropAdapter.LOCATION_BEFORE;
    public static final int LOCATION_AFTER = JdtViewerDropAdapter.LOCATION_AFTER;
    public static final int LOCATION_ON = JdtViewerDropAdapter.LOCATION_ON;

	public Object getDestination();

	public int getLocation();
}
