package uk.ac.ebi.ena.sra.cram.format;

import java.util.Arrays;

public class ByteFrequencies {
	private int[] frequencies;

	public ByteFrequencies(int size) {
		frequencies = new int[size];
	}

	public ByteFrequencies() {
		frequencies = new int[256];
	}

	public ByteFrequencies(byte[] values, int[] freqs) {
		frequencies = new int[256];

		for (int i = 0; i < values.length; i++)
			frequencies[0xFF & values[i]] = freqs[i];
	}

	public void add(byte value) {
		frequencies[0xFF & value]++;
	}

	public void add(byte value, int howMany) {
		frequencies[0xFF & value] += howMany;
	}

	public void add(byte[] bytes) {
		for (byte b : bytes)
			frequencies[0xFF & b]++;
	}

	public int getFrequency(byte value) {
		return frequencies[0xFF & value];
	}

	public byte[] getValues() {
		int size = 0;
		for (int i = 0; i < frequencies.length; i++) {
			if (frequencies[i] > 0)
				size++;
		}

		int valueIndex = 0;
		byte[] collapsedValueArray = new byte[size];
		for (int i = 0; i < frequencies.length; i++) {
			if (frequencies[i] > 0)
				collapsedValueArray[valueIndex++] = (byte) i;
		}

		return collapsedValueArray;
	}

	public int[] getFrequencies() {
		int size = 0;
		for (int i = 0; i < frequencies.length; i++) {
			if (frequencies[i] > 0)
				size++;
		}

		int frequencyIndex = 0;
		int[] collapsedFrequencyArray = new int[size];
		for (int i = 0; i < frequencies.length; i++) {
			if (frequencies[i] > 0)
				collapsedFrequencyArray[frequencyIndex++] = frequencies[i];
		}

		return collapsedFrequencyArray;
	}

	@Override
	public String toString() {
		StringBuffer sb = new StringBuffer();
		sb.append(Arrays.toString(getValues()));
		sb.append(Arrays.toString(getFrequencies()));
		return sb.toString();
	}
}
