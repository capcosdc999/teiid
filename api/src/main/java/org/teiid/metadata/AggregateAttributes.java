/*
 * JBoss, Home of Professional Open Source.
 * See the COPYRIGHT.txt file distributed with this work for information
 * regarding copyright ownership.  Some portions may be licensed
 * to Red Hat, Inc. under one or more contributor license agreements.
 * 
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 * 
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA
 * 02110-1301 USA.
 */

package org.teiid.metadata;

import java.io.Serializable;

/**
 * Holds metadata related to user defined aggregate functions.
 */
public class AggregateAttributes implements Serializable {
	
	private static final long serialVersionUID = 5398000844375944790L;

	private boolean allowsDistinct;
	private boolean windowable;
	private boolean decomposable;
	private boolean respectsNulls;
	private boolean allowsOrderBy;
	
	public boolean allowsOrderBy() {
		return allowsOrderBy;
	}
	
	public void setAllowsOrderBy(boolean allowsOrderBy) {
		this.allowsOrderBy = allowsOrderBy;
	}
	
	public boolean allowsDistinct() {
		return allowsDistinct;
	}
	
	public void setAllowsDistinct(boolean allowsDistinct) {
		this.allowsDistinct = allowsDistinct;
	}
	
	public boolean isWindowable() {
		return windowable;
	}
	
	public void setWindowable(boolean windowable) {
		this.windowable = windowable;
	}
	
	public boolean isDecomposable() {
		return decomposable;
	}
	
	public void setDecomposable(boolean decomposable) {
		this.decomposable = decomposable;
	}
	
	public boolean respectsNulls() {
		return respectsNulls;
	}
	
	public void setRespectsNulls(boolean respectsNulls) {
		this.respectsNulls = respectsNulls;
	}
	
}
