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
package uk.ac.ebi.ena.sra.cram.encoding;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.log4j.Logger;

import uk.ac.ebi.ena.sra.cram.SequenceBaseProvider;
import uk.ac.ebi.ena.sra.cram.format.CramRecord;
import uk.ac.ebi.ena.sra.cram.format.ReadAnnotation;
import uk.ac.ebi.ena.sra.cram.format.ReadFeature;
import uk.ac.ebi.ena.sra.cram.format.ReadTag;
import uk.ac.ebi.ena.sra.cram.io.BitInputStream;
import uk.ac.ebi.ena.sra.cram.io.BitOutputStream;
import uk.ac.ebi.ena.sra.cram.io.NullBitOutputStream;

public class CramRecordCodec_old implements BitCodec<CramRecord> {
	public BitCodec<Long> inSeqPosCodec;
	public BitCodec<Long> recordsToNextFragmentCodec;
	public BitCodec<Long> readlengthCodec;
	public BitCodec<List<ReadFeature>> variationsCodec;
	public SequenceBaseProvider sequenceBaseProvider;

	public BitCodec<Byte> baseCodec;
	public ByteArrayBitCodec qualityCodec;

	public String sequenceName;
	public long prevPosInSeq = 1L;
	public long defaultReadLength = 0L;

	public BitCodec<ReadAnnotation> readAnnoCodec;
	public BitCodec<Integer> readGroupCodec;
	public BitCodec<Long> nextFragmentIDCodec;

	public BitCodec<Byte> mappingQualityCodec;

	public boolean storeMappedQualityScores = false;
	public BitCodec<Byte> heapByteCodec;

	public Map<String, BitCodec<byte[]>> tagCodecMap;
	public BitCodec<String> tagKeyAndTypeCodec;

	public BitCodec<Byte> flagsCodec;

	private static Logger log = Logger.getLogger(CramRecordCodec.class);

	private static int debugRecordEndMarkerLen = 21;
	private static long debugRecordEndMarker = ~(-1 << (debugRecordEndMarkerLen / 2));

	public BitCodec<byte[]> readNameCodec;
	public boolean preserveReadNames = false;

	private ByteBuffer tagBuffer = ByteBuffer.allocate(1024 * 10);

	@Override
	public CramRecord read(BitInputStream bis) throws IOException {
		recordCounter++;

		long marker = bis.readLongBits(debugRecordEndMarkerLen);
		if (marker != debugRecordEndMarker) {
			throw new RuntimeException("Debug marker for beginning of record not found.");
		}

		CramRecord record = new CramRecord();

		byte b = flagsCodec.read(bis);
		record.setFlags(b);

		String readName = null;
		if (preserveReadNames)
			readName = new String(readNameCodec.read(bis));

		if (!record.isLastFragment()) {
			if (!record.detached) {
				record.setRecordsToNextFragment(recordsToNextFragmentCodec.read(bis));
			} else {
				CramRecord mate = new CramRecord();
				mate.setReadMapped(bis.readBit());
				mate.setNegativeStrand(bis.readBit());
				mate.setFirstInPair(bis.readBit());
				if (readName == null)
					readName = new String(readNameCodec.read(bis));
				mate.setReadName(readName);
				mate.setSequenceName(readZeroTerminatedString(heapByteCodec, bis));
				mate.setAlignmentStart(Long.valueOf(readZeroTerminatedString(heapByteCodec, bis)));
				record.insertSize = Integer.valueOf(readZeroTerminatedString(heapByteCodec, bis));

				mate.setFirstInPair(!record.isFirstInPair());
				if (record.isFirstInPair())
					record.next = mate;
				else
					record.previous = mate;
			}
		}
		record.setReadName(readName);

		int readLen;
		if (bis.readBit())
			readLen = readlengthCodec.read(bis).intValue();
		else
			readLen = (int) defaultReadLength;
		record.setReadLength(readLen);

		record.setReadGroupID(readGroupCodec.read(bis));

		long position = prevPosInSeq + inSeqPosCodec.read(bis);
		prevPosInSeq = position;
		record.setAlignmentStart(position);

		boolean hasTags = bis.readBit();

		// while (bis.readBit()) {
		// if (record.tags == null)
		// record.tags = new ArrayList<ReadTag>();
		// String tagKeyAndType = tagKeyAndTypeCodec.read(bis);
		// BitCodec<byte[]> codec = tagCodecMap.get(tagKeyAndType);
		// byte[] valueBytes = codec.read(bis);
		// char type = tagKeyAndType.charAt(3);
		// Object value = ReadTag.restoreValueFromByteArray(type, valueBytes);
		//
		// ReadTag tag = new ReadTag(tagKeyAndType.substring(0, 2), type,
		// value);
		// record.tags.add(tag);
		// }
		//
		// boolean oqTagPresent = bis.readBit();

		checkMarker(bis, record);

		boolean bisByteAligned = false;

		if (record.isReadMapped()) {
			boolean imperfectMatch = bis.readBit();
			if (imperfectMatch) {
				List<ReadFeature> features = variationsCodec.read(bis);
				record.setReadFeatures(features);
			}

			record.setMappingQuality(mappingQualityCodec.read(bis));

			checkMarker(bis, record);

			if (storeMappedQualityScores) {
				boolean hasQS = bis.readBit();
				if (hasQS) {
					bis.alignToByte();
					bisByteAligned = true;
					if (hasQS) {
						byte[] scores = new byte[readLen];
						bis.readAlignedBytes(scores);
						record.setQualityScores(scores);
					}
				}

				// byte[] scores = qualityCodec.read(bis, readLen);
				// record.setQualityScores(scores);
			}

		} else {
			boolean hasQS = bis.readBit();
			bis.alignToByte();
			bisByteAligned = true;

			byte[] bases = new byte[readLen];
			bis.readAlignedBytes(bases);
			record.setReadBases(bases);

			checkMarker(bis, record);

			if (hasQS) {
				byte[] scores = new byte[readLen];
				bis.readAlignedBytes(scores);
				record.setQualityScores(scores);
			}

			// byte[] bases = new byte[readLen];
			// readNonEmptyByteArray(bis, bases, baseCodec);
			// record.setReadBases(bases);
			//
			// if (bis.readBit()) {
			// byte[] scores = qualityCodec.read(bis, readLen);
			// record.setQualityScores(scores);
			// }

		}

		// if (oqTagPresent) {
		// if (!bisByteAligned) {
		// bis.alignToByte();
		// bisByteAligned = true;
		// }
		//
		// byte[] oq = new byte[readLen];
		// bis.readAlignedBytes(oq);
		// if (record.tags == null)
		// record.tags = new ArrayList<ReadTag>();
		// record.tags.add(new ReadTag("OQ", 'Z', oq));
		// }

		// checkMarker(bis, record);

		if (hasTags) {
			if (!bisByteAligned) {
				bis.alignToByte();
				bisByteAligned = true;
			}
			byte tagCount = bis.readByte();
			if (recordCounter < 10)
				System.out.println("Record " + recordCounter + ": tag count: " + tagCount);
			record.tags = new ArrayList<ReadTag>();

			for (int i = 0; i < (0xFF & tagCount); i++) {
				String tagKeyAndType = tagKeyAndTypeCodec.read(bis);
				BitCodec<byte[]> codec = tagCodecMap.get(tagKeyAndType);
				byte[] valueBytes = codec.read(bis);
				char type = tagKeyAndType.charAt(3);
				Object value = ReadTag.restoreValueFromByteArray(type, valueBytes);

				ReadTag tag = new ReadTag(tagKeyAndType.substring(0, 2), type, value);
				record.tags.add(tag);

				// b = 0;
				// tagBuffer.clear();
				// while ((b = bis.readByte()) != 0) {
				// tagBuffer.put(b);
				// }
				// char type = (char) tagBuffer.get(3);
				//
				// tagBuffer.flip();
				// Object value = ReadTag.restoreValueFromByteArray(type,
				// tagBuffer.array(), 4, tagBuffer.limit() - 4);
				//
				// ReadTag tag = new ReadTag(new String(tagBuffer.array(), 0,
				// 2), type, value);
				// record.tags.add(tag);
			}
		}

		marker = bis.readLongBits(debugRecordEndMarkerLen);
		if (marker != debugRecordEndMarker) {
			System.out.println(record.toString());
			throw new RuntimeException("Debug marker for end of record not found.");
		}

		return record;
	}

	private void checkMarker(BitInputStream bis, CramRecord record) throws IOException {
		long marker = bis.readLongBits(debugRecordEndMarkerLen);
		if (marker != debugRecordEndMarker) {
			System.err.println("Record counter=" + recordCounter);
			System.err.println("Record so far: " + record.toString());
			throw new RuntimeException("Oops.");
		}

	}

	private long recordCounter = 0;

	@Override
	public long write(BitOutputStream bos, CramRecord record) throws IOException {
		recordCounter++;
		if (recordCounter == 1)
			System.err.println(record.toString());

		bos.write(debugRecordEndMarker, debugRecordEndMarkerLen);

		long len = 0L;

		len += flagsCodec.write(bos, record.getFlags());

		if (preserveReadNames)
			len += readNameCodec.write(bos, record.getReadName().getBytes());

		if (!record.isLastFragment()) {
			if (record.getRecordsToNextFragment() > 0) {
				len += recordsToNextFragmentCodec.write(bos, record.getRecordsToNextFragment());
			} else {

				CramRecord mate = record.next == null ? record.previous : record.next;
				bos.write(mate.isReadMapped());
				bos.write(mate.isNegativeStrand());
				bos.write(mate.isFirstInPair());
				if (!preserveReadNames) {
					len += readNameCodec.write(bos, record.getReadName().getBytes());
				}
				len += writeZeroTerminatedString(mate.getSequenceName(), heapByteCodec, bos);
				len += writeZeroTerminatedString(String.valueOf(mate.getAlignmentStart()), heapByteCodec, bos);
				len += writeZeroTerminatedString(String.valueOf(record.insertSize), heapByteCodec, bos);
			}
		}

		if (record.getReadLength() != defaultReadLength) {
			bos.write(true);
			len += readlengthCodec.write(bos, record.getReadLength());
		} else
			bos.write(false);
		len++;

		len += readGroupCodec.write(bos, record.getReadGroupID());

		len += inSeqPosCodec.write(bos, record.getAlignmentStart() - prevPosInSeq);
		prevPosInSeq = record.getAlignmentStart();

		boolean hasTags = record.tags != null && !record.tags.isEmpty();
		bos.write(hasTags);
		len++;

		bos.write(debugRecordEndMarker, debugRecordEndMarkerLen);

		// ReadTag oqTag = null;
		// if (record.tags != null && !record.tags.isEmpty()) {
		// for (ReadTag tag : record.tags) {
		// if ("OQ".equals(tag.getKey())) {
		// oqTag = tag;
		// continue;
		// }
		// bos.write(true);
		// len++;
		// tagKeyAndTypeCodec.write(bos, tag.getKeyAndType());
		// BitCodec<byte[]> codec = tagCodecMap.get(tag.getKeyAndType());
		// long bits = codec.write(bos, tag.getValueAsByteArray());
		// len += bits;
		// }
		// }
		// bos.write(false);
		// len++;
		// bos.write(oqTag != null);
		// len++;

		boolean bosByteAligned = false;

		if (record.isReadMapped()) {
			if (record.getAlignmentStart() - prevPosInSeq < 0) {
				log.error("Negative relative position in sequence: prev=" + prevPosInSeq);
				log.error(record.toString());
			}
			List<ReadFeature> vars = record.getReadFeatures();
			if (vars == null || vars.isEmpty())
				bos.write(false);
			else {
				bos.write(true);
				len += variationsCodec.write(bos, vars);
			}
			len++;

			len += mappingQualityCodec.write(bos, record.getMappingQuality());
			bos.write(debugRecordEndMarker, debugRecordEndMarkerLen);

			if (storeMappedQualityScores) {
				// len += qualityCodec.write(bos, record.getQualityScores());

				if (record.getQualityScores() == null || record.getQualityScores().length == 0) {
					bos.write(false);
				} else {
					bos.write(true);
				}
				len++;

				len += bos.alignToByte();
				bosByteAligned = true;

				if (record.getQualityScores() != null && record.getQualityScores().length != 0) {
					bos.write(record.getQualityScores());
					len += 8 * record.getQualityScores().length;
				}
			}

		} else {
			if (record.getAlignmentStart() - prevPosInSeq < 0) {
				log.error("Negative relative position in sequence: prev=" + prevPosInSeq);
				log.error(record.toString());
			}

			if (record.getQualityScores() == null || record.getQualityScores().length == 0) {
				bos.write(false);
			} else {
				bos.write(true);
			}
			len++;

			len += bos.alignToByte();
			bosByteAligned = true;

			bos.write(record.getReadBases());
			len += 8 * record.getReadBases().length;

			bos.write(debugRecordEndMarker, debugRecordEndMarkerLen);

			if (record.getQualityScores() != null && record.getQualityScores().length != 0) {
				bos.write(record.getQualityScores());
				len += 8 * record.getQualityScores().length;
			}

			// len += writeNonEmptyByteArray(bos, record.getReadBases(),
			// baseCodec);
			//
			// if (record.getQualityScores() == null ||
			// record.getQualityScores().length == 0) {
			// bos.write(false);
			// } else {
			// bos.write(true);
			// len += qualityCodec.write(bos, record.getQualityScores());
			// }

		}

		// if (oqTag != null) {
		// if (!bosByteAligned) {
		// len += bos.alignToByte();
		// bosByteAligned = true;
		// }
		//
		// byte[] bytes = oqTag.getValueAsByteArray() ;
		// bos.write(bytes);
		// len += 8*bytes.length ;
		// }

		// bos.write(debugRecordEndMarker, debugRecordEndMarkerLen);

		if (hasTags) {
			if (!bosByteAligned) {
				len += bos.alignToByte();
				bosByteAligned = true;
			}
			if (recordCounter < 10)
				System.out.println("Record " + recordCounter + ": tag count: " + record.tags.size());
			bos.write((byte) record.tags.size());
			len += 8;
			for (ReadTag tag : record.tags) {
				byte[] keyAndType = tag.getKeyAndType().getBytes();
				bos.write(keyAndType);
				len += 8 * keyAndType.length;
				byte[] value = tag.getValueAsByteArray();
				bos.write(value);
				len += 8 * value.length;
				bos.write((byte) 0);
			}
		}

		bos.write(debugRecordEndMarker, debugRecordEndMarkerLen);

		return len;
	}

	@Override
	public long numberOfBits(CramRecord record) {
		try {
			return write(NullBitOutputStream.INSTANCE, record);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	private static int writeNonEmptyByteArray(BitOutputStream bos, byte[] array, BitCodec<Byte> codec)
			throws IOException {
		if (array == null || array.length == 0)
			throw new RuntimeException("Expecting a non-empty array.");

		int len = 0;
		for (byte b : array)
			len += codec.write(bos, b);
		return len;
	}

	private static byte[] readNonEmptyByteArray(BitInputStream bis, byte[] array, BitCodec<Byte> codec)
			throws IOException {
		for (int i = 0; i < array.length; i++)
			array[i] = codec.read(bis);

		return array;
	}

	private static long writeZeroTerminatedString(String string, BitCodec<Byte> codec, BitOutputStream bos)
			throws IOException {
		long len = 0;
		for (byte b : string.getBytes()) {
			len += codec.write(bos, b);
		}

		len += codec.write(bos, (byte) 0);
		return len;
	}

	private static final int maxBufferSize = 1024;
	private static java.nio.ByteBuffer byteBuffer = java.nio.ByteBuffer.allocate(maxBufferSize);

	private static String readZeroTerminatedString(BitCodec<Byte> codec, BitInputStream bis) throws IOException {
		byteBuffer.clear();
		for (int i = 0; i < maxBufferSize; i++) {
			byte b = codec.read(bis);
			if (b == 0)
				break;
			byteBuffer.put(b);
		}
		if (byteBuffer.position() >= maxBufferSize)
			throw new RuntimeException("Buffer overflow while reading string. ");

		byteBuffer.flip();
		byte[] bytes = new byte[byteBuffer.limit()];
		byteBuffer.get(bytes);
		return new String(bytes);
	}
}
