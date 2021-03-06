/*******************************************************************************
 * Copyright 2012 EMBL-EBI, Hinxton outstation
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/
package uk.ac.ebi.ena.sra.cram.format;

import java.io.Serializable;

public class InsertBase implements Serializable, ReadFeature {

	private int position;
	private byte base;

	public InsertBase() {
	}

	public InsertBase(int position, byte base) {
		this.position = position;
		this.base = base;
	}

	public static final byte operator = 'i';

	@Override
	public byte getOperator() {
		return operator;
	}

	public int getPosition() {
		return position;
	}

	public void setPosition(int position) {
		this.position = position;
	}

	@Override
	public boolean equals(Object obj) {
		if (!(obj instanceof InsertBase))
			return false;

		InsertBase v = (InsertBase) obj;

		if (position != v.position)
			return false;

		if (base != v.base)
			return false;

		return true;
	}

	@Override
	public String toString() {
		StringBuffer sb = new StringBuffer(getClass().getSimpleName() + "[");
		sb.append("position=").append(position);
		sb.append("; base=").appendCodePoint(base);
		sb.append("] ");
		return sb.toString();
	}

	public byte getBase() {
		return base;
	}

	public void setBase(byte base) {
		this.base = base;
	}
}
