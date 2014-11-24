package com.itextpdf.core.pdf;

import com.itextpdf.basics.PdfException;
import com.itextpdf.basics.io.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

public class PdfReader {

    protected static boolean correctStreamLength = true;

    protected PdfTokeniser tokens;
    protected char pdfVersion;
    protected long lastXref;
    protected long eofPos;
    protected PdfDictionary trailer;
    protected PdfDocument pdfDocument;

    protected boolean rebuildXref = false;

    private static final String endstream1 = "endstream";
    private static final String endstream2 = "\nendstream";
    private static final String endstream3 = "\r\nendstream";
    private static final String endstream4 = "\rendstream";
    private static final byte[] endstream = PdfOutputStream.getIsoBytes("endstream");
    private static final byte[] endobj = PdfOutputStream.getIsoBytes("endobj");

    public PdfReader(InputStream is) throws IOException, PdfException {
        this(new RandomAccessSourceFactory().createSource(is));
    }

    /**
     * Constructs a new PdfReader.  This is the master constructor.
     *
     * @param byteSource source of bytes for the reader
     *                   TODO param closeSourceOnConstructorError if true, the byteSource will be closed if there is an error during construction of this reader
     */
    public PdfReader(RandomAccessSource byteSource) throws IOException, PdfException {
        tokens = getOffsetTokeniser(byteSource);
    }

    public void close() throws IOException {
        tokens.close();
    }

    public boolean isCloseStream() {
        return tokens.isCloseStream();
    }

    public void setCloseStream(boolean closeStream) {
        tokens.setCloseStream(closeStream);
    }

    /**
     * Parses the entire PDF
     */
    protected void readPdf() throws IOException, PdfException {
        try {
            readXref();
        } catch (Exception ex) {
            rebuildXref();
        }
    }

    protected void readObjectStream(PdfStream objectStream) throws PdfException, IOException {
        int objectStreamNumber = objectStream.getIndirectReference().getObjNr();
        int first = objectStream.getAsNumber(PdfName.First).getIntValue();
        int n = objectStream.getAsNumber(PdfName.N).getIntValue();
        byte[] bytes = objectStream.getInputStreamBytes(true);
        PdfTokeniser saveTokens = tokens;
        try {
            tokens = new PdfTokeniser(new RandomAccessFileOrArray(new RandomAccessSourceFactory().createSource(bytes)));
            int address[] = new int[n];
            int objNumber[] = new int[n];
            boolean ok = true;
            for (int k = 0; k < n; ++k) {
                ok = tokens.nextToken();
                if (!ok)
                    break;
                if (tokens.getTokenType() != PdfTokeniser.TokenType.Number) {
                    ok = false;
                    break;
                }
                objNumber[k] = tokens.getIntValue();
                ok = tokens.nextToken();
                if (!ok)
                    break;
                if (tokens.getTokenType() != PdfTokeniser.TokenType.Number) {
                    ok = false;
                    break;
                }
                address[k] = tokens.getIntValue() + first;
            }
            if (!ok)
                throw new PdfException(PdfException.ErrorReadingObjectStream);
            for (int k = 0; k < n; ++k) {
                tokens.seek(address[k]);
                tokens.nextToken();
                PdfObject obj;
                if (tokens.getTokenType() == PdfTokeniser.TokenType.Number) {
                    obj = new PdfNumber(tokens.getByteContent());
                } else {
                    tokens.seek(address[k]);
                    obj = readObject(false);
                }
                PdfIndirectReference reference = pdfDocument.getXref().get(objNumber[k]);
                // Check if this object has no incremental updates (e.g. no append mode)
                if (reference.getObjectStreamNumber() == objectStreamNumber) {
                    reference.setRefersTo(obj);
                }
            }
            objectStream.getIndirectReference().setFree();
        } finally {
            tokens = saveTokens;
        }
    }

    protected PdfObject readObject(PdfIndirectReference reference) throws PdfException {
        return readObject(reference, true);
    }

    protected PdfObject readObject(boolean readAsDirect) throws IOException, PdfException {
        tokens.nextValidToken();
        PdfTokeniser.TokenType type = tokens.getTokenType();
        switch (type) {
            case StartDic: {
                PdfDictionary dic = readDictionary();
                long pos = tokens.getPosition();
                // be careful in the trailer. May not be a "next" token.
                boolean hasNext;
                do {
                    hasNext = tokens.nextToken();
                } while (hasNext && tokens.getTokenType() == PdfTokeniser.TokenType.Comment);

                if (hasNext && tokens.tokenValueEqualsTo(PdfTokeniser.Stream)) {
                    //skip whitespaces
                    int ch;
                    do {
                        ch = tokens.read();
                    } while (ch == 32 || ch == 9 || ch == 0 || ch == 12);
                    if (ch != '\n')
                        ch = tokens.read();
                    if (ch != '\n')
                        tokens.backOnePosition(ch);
                    PdfStream stream = new PdfStream(tokens.getPosition());
                    stream.putAll(dic);
                    return stream;
                } else {
                    tokens.seek(pos);
                    return dic;
                }
            }
            case StartArray:
                return readArray();
            case Number:
                return new PdfNumber(tokens.getByteContent());
            case String:
                return new PdfString(tokens.getByteContent()).setHexWriting(tokens.isHexString());
            case Name:
                return readPdfName(readAsDirect);
            case Ref:
                int num = tokens.getObjNr();
                PdfXrefTable table = pdfDocument.getXref();
                PdfIndirectReference reference = table.get(num);
                if (reference != null) {
                    if (reference.getGenNr() != tokens.getGenNr()) {
                        throw new PdfException(PdfException.InvalidIndirectReference1).setMessageParams(reference);
                    }
                    return reference;
                } else {
                    PdfIndirectReference ref = new PdfIndirectReference(pdfDocument,
                            num, tokens.getGenNr(), 0).setState(PdfIndirectReference.Reading);
                    table.add(ref);
                    return ref;
                }
            case EndOfFile:
                throw new PdfException(PdfException.UnexpectedEndOfFile);
            default:
                if (tokens.tokenValueEqualsTo(PdfTokeniser.Null)) {
                    if (readAsDirect) {
                        return PdfNull.PdfNull;
                    } else {
                        return new PdfNull();
                    }
                } else if (tokens.tokenValueEqualsTo(PdfTokeniser.True)) {
                    if (readAsDirect) {
                        return PdfBoolean.PdfTrue;
                    } else {
                        return new PdfBoolean(true);
                    }
                } else if (tokens.tokenValueEqualsTo(PdfTokeniser.False)) {
                    if (readAsDirect) {
                        return PdfBoolean.PdfFalse;
                    } else {
                        return new PdfBoolean(false);
                    }
                }
                return null;
        }
    }

    protected PdfName readPdfName(boolean readAsDirect) {
        if (readAsDirect) {
            PdfName cachedName = PdfName.staticNames.get(tokens.getStringValue());
            if (cachedName != null)
                return cachedName;
        }
        // an indirect name (how odd...), or a non-standard one
        return new PdfName(tokens.getByteContent());
    }

    protected PdfDictionary readDictionary() throws IOException, PdfException {
        PdfDictionary dic = new PdfDictionary();
        while (true) {
            tokens.nextValidToken();
            if (tokens.getTokenType() == PdfTokeniser.TokenType.EndDic)
                break;
            if (tokens.getTokenType() != PdfTokeniser.TokenType.Name)
                tokens.throwError(PdfException.DictionaryKey1IsNotAName, tokens.getStringValue());
            PdfName name = readPdfName(true);
            PdfObject obj = readObject(true);
            if (obj == null) {
                if (tokens.getTokenType() == PdfTokeniser.TokenType.EndDic)
                    tokens.throwError(PdfException.UnexpectedGtGt);
                if (tokens.getTokenType() == PdfTokeniser.TokenType.EndArray)
                    tokens.throwError(PdfException.UnexpectedCloseBracket);
            }
            dic.put(name, obj);
        }
        return dic;
    }

    protected PdfArray readArray() throws IOException, PdfException {
        PdfArray array = new PdfArray();
        while (true) {
            PdfObject obj = readObject(true);
            if (obj == null) {
                if (tokens.getTokenType() == PdfTokeniser.TokenType.EndArray)
                    break;
                if (tokens.getTokenType() == PdfTokeniser.TokenType.EndDic)
                    tokens.throwError(PdfException.UnexpectedGtGt);
            }
            array.add(obj);
        }
        return array;
    }

    protected void readXref() throws IOException, PdfException {
        tokens.seek(tokens.getStartxref());
        tokens.nextToken();
        if (!tokens.tokenValueEqualsTo(PdfTokeniser.Startxref))
            throw new PdfException(PdfException.PdfStartxrefNotFound, tokens);
        tokens.nextToken();
        if (tokens.getTokenType() != PdfTokeniser.TokenType.Number)
            throw new PdfException(PdfException.PdfStartxrefIsNotFollowedByANumber, tokens);
        long startxref = tokens.getLongValue();
        lastXref = startxref;
        eofPos = tokens.getPosition();
        try {
            if (readXrefStream(startxref)) return;
        } catch (Exception e) {
        }
        // clear xref because of possible issues at reading xref stream.
        pdfDocument.getXref().clear();

        tokens.seek(startxref);
        trailer = readXrefSection();

        //  Prev key - integer value
        //  (Present only if the file has more than one cross-reference section; shall be an indirect reference)
        // The byte offset in the decoded stream from the beginning of the file
        // to the beginning of the previous cross-reference section.
        PdfDictionary trailer2 = trailer;
        while (true) {
            PdfNumber prev = (PdfNumber) trailer2.get(PdfName.Prev);
            if (prev == null)
                break;
            tokens.seek(prev.getLongValue());
            trailer2 = readXrefSection();
        }
    }

    protected PdfDictionary readXrefSection() throws IOException, PdfException {
        tokens.nextValidToken();
        if (!tokens.tokenValueEqualsTo(PdfTokeniser.Xref))
            tokens.throwError(PdfException.XrefSubsectionNotFound);
        PdfXrefTable xref = pdfDocument.getXref();
        int end = 0;
        while (true) {
            tokens.nextValidToken();
            if (tokens.tokenValueEqualsTo(PdfTokeniser.Trailer))
                break;
            if (tokens.getTokenType() != PdfTokeniser.TokenType.Number)
                tokens.throwError(PdfException.ObjectNumberOfTheFirstObjectInThisXrefSubsectionNotFound);
            int start = tokens.getIntValue();
            tokens.nextValidToken();
            if (tokens.getTokenType() != PdfTokeniser.TokenType.Number)
                tokens.throwError(PdfException.NumberOfEntriesInThisXrefSubsectionNotFound);
            end = tokens.getIntValue() + start;
            for (int num = start; num < end; ++num) {
                tokens.nextValidToken();
                long pos = tokens.getLongValue();
                tokens.nextValidToken();
                int gen = tokens.getIntValue();
                tokens.nextValidToken();
                PdfIndirectReference reference = xref.get(num);
                if (reference == null) {
                    reference = new PdfIndirectReference(pdfDocument, num, gen, pos);
                } else if (reference.checkState(PdfIndirectReference.Reading) && reference.getGenNr() == gen) {
                    reference.setOffsetOrIndex(pos);
                } else {
                    continue;
                }
                if (tokens.tokenValueEqualsTo(PdfTokeniser.N)) {
                    if (xref.get(num) == null) {
                        if (pos == 0)
                            tokens.throwError(PdfException.FilePosition0CrossReferenceEntryInThisXrefSubsection);
                        xref.add(reference);
                    }
                } else if (tokens.tokenValueEqualsTo(PdfTokeniser.F)) {
                    if (xref.get(num) == null) {
                        reference.setFree();
                        xref.add(reference);
                    }
                } else
                    tokens.throwError(PdfException.InvalidCrossReferenceEntryInThisXrefSubsection);
            }
        }
        PdfDictionary trailer = (PdfDictionary) readObject(false);
        PdfNumber xrefSize = (PdfNumber) trailer.get(PdfName.Size);
        if (xrefSize == null || xrefSize.getIntValue() != end) {
            throw new PdfException(PdfException.InvalidXrefSection);
        }

        PdfObject xrs = trailer.get(PdfName.XRefStm);
        if (xrs != null && xrs.getType() == PdfObject.Number) {
            int loc = ((PdfNumber) xrs).getIntValue();
            try {
                readXrefStream(loc);
            } catch (IOException e) {
                xref.clear();
                throw e;
            }
        }
        xref.setXRefStm(false);
        xref.updateNextObjectNumber();
        return trailer;
    }

    protected boolean readXrefStream(final long ptr) throws IOException, PdfException {
        tokens.seek(ptr);
        if (!tokens.nextToken())
            return false;
        if (tokens.getTokenType() != PdfTokeniser.TokenType.Number)
            return false;
        if (!tokens.nextToken() || tokens.getTokenType() != PdfTokeniser.TokenType.Number)
            return false;
        if (!tokens.nextToken() || !tokens.tokenValueEqualsTo(PdfTokeniser.Obj))
            return false;
        PdfXrefTable xref = pdfDocument.getXref();
        PdfObject object = readObject(false);
        PdfStream xrefStream;
        if (object.getType() == PdfObject.Stream) {
            xrefStream = (PdfStream) object;
            if (!PdfName.XRef.equals(xrefStream.get(PdfName.Type)))
                return false;
        } else
            return false;
        if (trailer == null) {
            trailer = new PdfDictionary();
            trailer.putAll(xrefStream);
        }

        int size = ((PdfNumber) xrefStream.get(PdfName.Size)).getIntValue();
        PdfArray index;
        PdfObject obj = xrefStream.get(PdfName.Index);
        if (obj == null) {
            index = new PdfArray();
            index.add(new PdfNumber(0));
            index.add(new PdfNumber(size));
        } else
            index = (PdfArray) obj;
        PdfArray w = xrefStream.getAsArray(PdfName.W);
        long prev = -1;
        obj = xrefStream.get(PdfName.Prev);
        if (obj != null)
            prev = ((PdfNumber) obj).getLongValue();
        xref.setCapacity(size);
        byte b[] = readStreamBytes(xrefStream, true);
        int bptr = 0;
        int wc[] = new int[3];
        for (int k = 0; k < 3; ++k)
            wc[k] = w.getAsNumber(k).getIntValue();
        for (int idx = 0; idx < index.size(); idx += 2) {
            int start = index.getAsNumber(idx).getIntValue();
            int length = index.getAsNumber(idx + 1).getIntValue();
            xref.setCapacity(start + length);
            while (length-- > 0) {
                int type = 1;
                if (wc[0] > 0) {
                    type = 0;
                    for (int k = 0; k < wc[0]; ++k)
                        type = (type << 8) + (b[bptr++] & 0xff);
                }
                long field2 = 0;
                for (int k = 0; k < wc[1]; ++k)
                    field2 = (field2 << 8) + (b[bptr++] & 0xff);
                int field3 = 0;
                for (int k = 0; k < wc[2]; ++k)
                    field3 = (field3 << 8) + (b[bptr++] & 0xff);
                int base = start;
                PdfIndirectReference newReference;
                switch (type) {
                    case 0:
                        newReference = new PdfIndirectReference(pdfDocument, base, field3, 0);
                        newReference.setFree();
                        break;
                    case 1:
                        newReference = new PdfIndirectReference(pdfDocument, base, field3, field2);
                        break;
                    case 2:
                        newReference = new PdfIndirectReference(pdfDocument, base, 0, field3);
                        newReference.setObjectStreamNumber((int) field2);
                        break;
                    default:
                        throw new PdfException(PdfException.InvalidXrefStream);
                }
                if (xref.get(base) == null) {
                    xref.add(newReference);
                } else if (xref.get(base).checkState(PdfIndirectReference.Reading)
                        && xref.get(base).getObjNr() == newReference.getObjNr()
                        && xref.get(base).getGenNr() == newReference.getGenNr()) {
                    PdfIndirectReference reference = xref.get(base);
                    reference.setOffsetOrIndex(newReference.getOffset());
                    reference.setObjectStreamNumber(newReference.getObjectStreamNumber());
                }
                ++start;
            }
        }
        xref.setXRefStm(true);
        xref.updateNextObjectNumber();
        return prev == -1 || readXrefStream(prev);
    }

    protected void fixXref() throws IOException, PdfException {
        PdfXrefTable xref = pdfDocument.getXref();
        tokens.seek(0);
        ByteBuffer buffer = new ByteBuffer(24);
        PdfTokeniser lineTokeniser = new PdfTokeniser(new RandomAccessFileOrArray(new PdfTokeniser.ReusableRandomAccessSource(buffer)));
        for (; ; ) {
            long pos = tokens.getPosition();
            buffer.reset();
            if (!tokens.readLineSegment(buffer, true)) // added boolean because of mailing list issue (17 Feb. 2014)
                break;
            if (buffer.get(0) >= '0' && buffer.get(0) <= '9') {
                int obj[] = PdfTokeniser.checkObjectStart(lineTokeniser);
                if (obj == null)
                    continue;
                int num = obj[0];
                int gen = obj[1];
                PdfIndirectReference reference = xref.get(num);
                if (reference != null && reference.getGenNr() == gen) {
                    reference.fixOffset(pos);
                }
            }
        }
    }

    protected void rebuildXref() throws IOException, PdfException {
        rebuildXref = true;
        PdfXrefTable xref = pdfDocument.getXref();
        xref.setXRefStm(false);
        xref.clear();
        tokens.seek(0);
        trailer = null;
        ByteBuffer buffer = new ByteBuffer(24);
        PdfTokeniser lineTokeniser = new PdfTokeniser(new RandomAccessFileOrArray(new PdfTokeniser.ReusableRandomAccessSource(buffer)));
        for (; ; ) {
            long pos = tokens.getPosition();
            buffer.reset();
            if (!tokens.readLineSegment(buffer, true)) // added boolean because of mailing list issue (17 Feb. 2014)
                break;
            if (buffer.get(0) == 't') {
                if (!PdfTokeniser.checkTrailer(buffer))
                    continue;
                tokens.seek(pos);
                tokens.nextToken();
                pos = tokens.getPosition();
                try {
                    PdfDictionary dic = (PdfDictionary) readObject(false);
                    if (dic.get(PdfName.Root, false) != null)
                        trailer = dic;
                    else
                        tokens.seek(pos);
                } catch (Exception e) {
                    tokens.seek(pos);
                }
            } else if (buffer.get(0) >= '0' && buffer.get(0) <= '9') {
                int obj[] = PdfTokeniser.checkObjectStart(lineTokeniser);
                if (obj == null)
                    continue;
                int num = obj[0];
                int gen = obj[1];
                if (xref.get(num) == null || xref.get(num).getGenNr() <= gen) {
                    xref.add(new PdfIndirectReference(pdfDocument, num, gen, pos));
                }
            }
        }
        xref.updateNextObjectNumber();
        if (trailer == null)
            throw new PdfException(PdfException.TrailerNotFound);
    }

    protected byte[] readStreamBytes(PdfStream stream, boolean decode) throws IOException, PdfException {
        PdfName type = stream.getAsName(PdfName.Type);
        if (!PdfName.XRefStm.equals(type) && !PdfName.ObjStm.equals(type))
            checkPdfStreamLength(stream);
        long offset = stream.getOffset();
        if (offset <= 0)
            return null;
        int length = stream.getLength();
        if (length <= 0)
            return new byte[0];
        RandomAccessFileOrArray file = tokens.getSafeFile();
        byte[] bytes = null;
        try {
            file.seek(stream.getOffset());
            bytes = new byte[length];
            file.readFully(bytes);
        } finally {
            try {
                file.close();
            } catch (Exception e) {
            }
        }
        return bytes;
    }

    protected InputStream readStream(PdfStream stream, boolean decode) throws IOException, PdfException {
        byte[] bytes = readStreamBytes(stream, decode);
        return bytes != null ? new ByteArrayInputStream(bytes) : null;
    }

    /**
     * Utility method that checks the provided byte source to see if it has junk bytes at the beginning.  If junk bytes
     * are found, construct a tokeniser that ignores the junk.  Otherwise, construct a tokeniser for the byte source as it is
     *
     * @param byteSource the source to check
     * @return a tokeniser that is guaranteed to start at the PDF header
     * @throws IOException if there is a problem reading the byte source
     */
    private static PdfTokeniser getOffsetTokeniser(RandomAccessSource byteSource) throws IOException, PdfException {
        PdfTokeniser tok = new PdfTokeniser(new RandomAccessFileOrArray(byteSource));
        int offset = tok.getHeaderOffset();
        if (offset != 0) {
            RandomAccessSource offsetSource = new WindowRandomAccessSource(byteSource, offset);
            tok = new PdfTokeniser(new RandomAccessFileOrArray(offsetSource));
        }
        return tok;
    }

    private PdfObject readObject(PdfIndirectReference reference, boolean fixXref) throws PdfException {
        if (reference == null)
            return null;
        if (reference.refersTo != null)
            return reference.refersTo;
        try {
            if (reference.getObjectStreamNumber() > 0) {
                PdfStream objectStream = (PdfStream) pdfDocument.getXref().
                        get(reference.getObjectStreamNumber()).getRefersTo(false);
                readObjectStream(objectStream);
                PdfObject object = reference.refersTo;
                return object != null ? object.setIndirectReference(reference) : null;
            } else if (reference.getOffset() > 0) {
                PdfObject object;
                try {
                    tokens.seek(reference.getOffset());
                    tokens.nextValidToken();
                    if (tokens.getTokenType() != PdfTokeniser.TokenType.Obj
                            || tokens.getObjNr() != reference.getObjNr()
                            || tokens.getGenNr() != reference.getGenNr()) {
                        tokens.throwError(PdfException.InvalidOffsetForObject1, reference.toString());
                    }
                    object = readObject(false);
                } catch (PdfException ex) {
                    if (fixXref && reference.getObjectStreamNumber() == 0) {
                        fixXref();
                        object = readObject(reference, false);
                    } else {
                        throw ex;
                    }
                }
                return object != null ? object.setIndirectReference(reference) : null;
            } else {
                return null;
            }
        } catch (IOException e) {
            throw new PdfException(PdfException.CannotReadPdfObject, e);
        }
    }

    private void checkPdfStreamLength(final PdfStream stream) throws PdfException, IOException {
        if (!correctStreamLength)
            return;
        long fileLength = tokens.length();
        long start = stream.getOffset();
        boolean calc = false;
        long streamLength = 0;
        PdfNumber pdfNumber = stream.getAsNumber(PdfName.Length);
        if (pdfNumber != null) {
            streamLength = pdfNumber.getIntValue();
            if (streamLength + start > fileLength - 20) {
                calc = true;
            } else {
                tokens.seek(start + streamLength);
                String line = tokens.readString(20);
                if (!line.startsWith(endstream2) && !line.startsWith(endstream3) &&
                        !line.startsWith(endstream4) && !line.startsWith(endstream1)) {
                    calc = true;
                }
            }
        } else {
            pdfNumber = new PdfNumber(0);
            stream.put(PdfName.Length, pdfNumber);
            calc = true;
        }
        if (calc) {
            ByteBuffer line = new ByteBuffer(16);
            tokens.seek(start);
            long pos;
            while (true) {
                pos = tokens.getPosition();
                line.reset();
                if (!tokens.readLineSegment(line, false)) // added boolean because of mailing list issue (17 Feb. 2014)
                    break;
                if (line.startsWith(endstream)) {
                    streamLength = pos - start;
                    break;
                }
                if (line.startsWith(endobj)) {
                    tokens.seek(pos - 16);
                    String s = tokens.readString(16);
                    int index = s.indexOf(endstream1);
                    if (index >= 0)
                        pos = pos - 16 + index;
                    streamLength = pos - start;
                    break;
                }
            }
            tokens.seek(pos - 2);
            if (tokens.read() == 13) {
                streamLength--;
            }
            tokens.seek(pos - 1);
            if (tokens.read() == 10) {
                streamLength--;
            }
            pdfNumber.setValue((int) streamLength);
        }
    }
}
