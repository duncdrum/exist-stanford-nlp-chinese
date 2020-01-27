/*
 *   exist-stanford-nlp-chinese: XQuery module to integrate the stanford named entity
 *   extraction library with eXist-db.
 *   Copyright (C) 2013 Wolfgang Meier and contributors
 *
 *   This program is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License as published by
 *   the Free Software Foundation, either version 3 of the License, or
 *   (at your option) any later version.
 *
 *   This program is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU General Public License for more details.
 *
 *   You should have received a copy of the GNU General Public License
 *   along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.exist.xquery.nlp.chinese;

import edu.stanford.nlp.ie.AbstractSequenceClassifier;
import edu.stanford.nlp.ling.CoreAnnotations;
import edu.stanford.nlp.ling.CoreLabel;
import edu.stanford.nlp.pipeline.Annotation;
import edu.stanford.nlp.pipeline.StanfordCoreNLP;
import edu.stanford.nlp.sequences.SeqClassifierFlags;
import org.exist.dom.QName;
import org.exist.dom.memtree.DocumentBuilderReceiver;
import org.exist.dom.memtree.MemTreeBuilder;
import org.exist.xquery.*;
import org.exist.xquery.value.*;
import org.xml.sax.SAXException;

import java.util.Iterator;
import java.util.List;
import java.util.Properties;

/**
 *
 */
public class StanfordNLPFunction extends BasicFunction {

    public static final String PREFIX = "stream2file";
    public static final String SUFFIX = ".tmp";

    /**
     *
     */
    public final static FunctionSignature signatures[] = {
            new FunctionSignature(
                new QName("classify-string", StanfordNLPModule.NAMESPACE_URI, StanfordNLPModule.PREFIX),
                "Classify the provided text string. Returns a sequence of text nodes and elements for " +
                "recognized entities.",
                new SequenceType[] {
                    new FunctionParameterSequenceType("annotators", Type.STRING, Cardinality.EXACTLY_ONE,
                        "The annotator property for the pipeline"),
                        new FunctionParameterSequenceType("text", Type.STRING, Cardinality.EXACTLY_ONE,
                                "String of text to analyze.")
                },
                new FunctionReturnSequenceType(Type.ELEMENT, Cardinality.EXACTLY_ONE,
                    "Sequence of text nodes and elements denoting recognized entities in the text")
            ),
            new FunctionSignature(
                    new QName("classify-node", StanfordNLPModule.NAMESPACE_URI, StanfordNLPModule.PREFIX),
                    "Mark up named entities in a node and all its sub-nodes. Returns a new in-memory document. " +
                            "Recognized entities are enclosed in inline elements.",
                    new SequenceType[] {
                            new FunctionParameterSequenceType("classifier", Type.ANY_URI, Cardinality.EXACTLY_ONE,
                                    "The path to the serialized classifier to load. Should point to a binary resource " +
                                            "stored within the database"),
                            new FunctionParameterSequenceType("node", Type.NODE, Cardinality.EXACTLY_ONE,
                                    "The node to process."),
                            new FunctionParameterSequenceType("callback", Type.FUNCTION_REFERENCE, Cardinality.EXACTLY_ONE,
                                    "A function item to be called for every entity found. Should take two parameters: " +
                                    "1) the name of the entity as string, 2) the content as string. The return value " +
                                    "of the function is inserted into the output.")
                    },
                    new FunctionReturnSequenceType(Type.NODE, Cardinality.EXACTLY_ONE,
                            "An in-memory node")
            )
    };

    private static AbstractSequenceClassifier<CoreLabel> cachedClassifier = null;
    private AnalyzeContextInfo cachedContextInfo = null;

    /**
     *
     * @param context
     * @param signature
     */
    public StanfordNLPFunction(XQueryContext context, FunctionSignature signature) {
        super(context, signature);
    }

    /**
     *
     * @param contextInfo
     * @throws XPathException
     */
    @Override
    public void analyze(AnalyzeContextInfo contextInfo) throws XPathException {
        cachedContextInfo = new AnalyzeContextInfo(contextInfo);
        super.analyze(cachedContextInfo);
    }

    /**
     *
     * @param args
     * @param contextSequence
     * @return
     * @throws XPathException
     */
    @Override
    public Sequence eval(Sequence[] args, Sequence contextSequence) throws XPathException {
        String annotators = args[0].getStringValue();

        Properties props = new Properties();
        props.put("annotators", annotators);
        StanfordCoreNLP pipeline = new StanfordCoreNLP(props);

        try {
            if (isCalledAs("classify-string")) {
                String text = args[1].getStringValue();
                final MemTreeBuilder builder = new MemTreeBuilder(context);

                if (builder == null) {
                    throw new XPathException("no builder");
                }

                final Annotation annotation = new Annotation(text);
                pipeline.annotate(annotation);

                Sequence result = null;
                try {
                    result = MemTreeOutputter.annotationToSequence(annotation, pipeline, builder);
                } catch (QName.IllegalQNameException e) {
                    e.printStackTrace();
                }
                return result;
            } else {
                NodeValue nv = (NodeValue) args[1].itemAt(0);
                FunctionReference callback = null;
                if (getArgumentCount() == 3) {
                    callback = (FunctionReference)args[2].itemAt(0);
                    callback.analyze(cachedContextInfo);
                }
                return classifyNode(nv, pipeline, callback);
            }
        } finally {
//            context.popDocumentContext();
        }
    }

    /**
     *
     * @param node
     * @param pipeline
     * @param callback
     * @return
     * @throws XPathException
     */
    private Sequence classifyNode(NodeValue node, StanfordCoreNLP pipeline, FunctionReference callback) throws XPathException {
        final Properties serializeOptions = new Properties();

        try {
            final MemTreeBuilder builder = context.getDocumentBuilder();
            final DocumentBuilderReceiver receiver = new NLPDocumentReceiver(builder, pipeline, callback);

            final int nodeNr = builder.getDocument().getLastNode();

            node.toSAX(context.getBroker(), receiver, serializeOptions);

            return builder.getDocument().getNode(nodeNr + 1);
        } catch (SAXException e) {
            throw new XPathException(this, e);
        }
    }

    /**
     *
     * @param text
     * @param builder
     * @param receiver
     * @param result
     * @param callback
     * @throws XPathException
     * @throws SAXException
     */
    private void classifyText(String text, MemTreeBuilder builder, NLPDocumentReceiver receiver, ValueSequence result, FunctionReference callback) throws XPathException, SAXException {
        StringBuilder buf = new StringBuilder();
        String background = SeqClassifierFlags.DEFAULT_BACKGROUND_SYMBOL;
        String prevTag = background;
        StanfordCoreNLP pipeline = receiver.getPipeline();
        final Annotation annotation = new Annotation(text);
        pipeline.annotate(annotation);

        int nodeNr = 0;
        List<List<CoreLabel>> out = cachedClassifier.classify(text);
        for (List<CoreLabel> sentence : out) {
            for (Iterator<CoreLabel> wordIter = sentence.iterator(); wordIter.hasNext(); ) {
                CoreLabel word = wordIter.next();
                final String current = word.get(CoreAnnotations.OriginalTextAnnotation.class);
                final String tag = word.get(CoreAnnotations.AnswerAnnotation.class);
                final String before = word.get(CoreAnnotations.BeforeAnnotation.class);
                final String after = word.get(CoreAnnotations.AfterAnnotation.class);
                if (!tag.equals(prevTag)) {
                    if (!prevTag.equals(background) && !tag.equals(background)) {
                        writeText(builder, buf, null);
                        builder.endElement();
                        if (result != null) {
                            result.add(builder.getDocument().getNode(nodeNr));
                        }
                        if (before != null)
                            buf.append(before);
                        writeText(builder, buf, result);
                        if (callback == null) {
                            final String name = tag.toLowerCase();
                            nodeNr = builder.startElement("", name, name, null);
                        }
                    } else if (!prevTag.equals(background)) {
                        if (callback == null) {
                            writeText(builder, buf, null);
                            builder.endElement();
                            if (result != null) {
                                result.add(builder.getDocument().getNode(nodeNr));
                            }
                        } else {
                            execCallback(callback, buf, prevTag, receiver);
                        }
                        if (before != null)
                            buf.append(before);
                    } else if (!tag.equals(background)) {
                        if (before != null)
                            buf.append(before);
                        writeText(builder, buf, result);
                        if (callback == null) {
                            final String name = tag.toLowerCase();
                            nodeNr = builder.startElement("", name, name, null);
                        }
                    }
                } else {
                    if (before != null)
                        buf.append(before);
                }
                buf.append(current);

                if (!tag.equals(background) && !wordIter.hasNext()) {
                    if (callback == null) {
                        writeText(builder, buf, result);
                        builder.endElement();
                    } else {
                        execCallback(callback, buf, tag, receiver);
                    }
                    prevTag = background;
                } else {
                    prevTag = tag;
                }
                if (after != null)
                    buf.append(after);
            }
        }
        writeText(builder, buf, result);
    }

    /**
     *
     * @param callback
     * @param buf
     * @param prevTag
     * @param receiver
     * @throws XPathException
     * @throws SAXException
     */
    private void execCallback(FunctionReference callback, StringBuilder buf, String prevTag, DocumentBuilderReceiver receiver) throws XPathException, SAXException {
        final StringValue tagName = new StringValue(prevTag);
        final StringValue content = new StringValue(buf.toString());
        Sequence result = callback.evalFunction(null, null, new Sequence[] { tagName, content });
        for (SequenceIterator iterator = result.iterate(); iterator.hasNext(); ) {
            Item next = iterator.nextItem();
            if (Type.subTypeOf(next.getType(), Type.NODE)) {
                next.copyTo(context.getBroker(), receiver);
            } else {
                receiver.characters(next.getStringValue());
            }
        }
        buf.setLength(0);
    }

    /**
     *
     * @param builder
     * @param buf
     * @param result
     */
    private void writeText(MemTreeBuilder builder, StringBuilder buf, ValueSequence result) {
        if (buf.length() > 0) {
            int node = builder.characters(buf.toString());
            if (result != null) {
                result.add(builder.getDocument().getNode(node));
            }
            buf.setLength(0);
        }
    }

    /**
     *
     */
    private class NLPDocumentReceiver extends DocumentBuilderReceiver {

        private MemTreeBuilder builder;
        private FunctionReference callback;
        private StanfordCoreNLP pipeline;
        private boolean inCallback = false;

        /**
         *
         * @param builder
         * @param pipeline
         * @param callback
         */
        public NLPDocumentReceiver(MemTreeBuilder builder, StanfordCoreNLP pipeline, FunctionReference callback) {
            super(builder, true);
            this.builder = builder;
            this.pipeline = pipeline;
            this.callback = callback;
        }

        /**
         *
         * @return
         */
        public StanfordCoreNLP getPipeline() {
            return this.pipeline;
        }

        /**
         *
         * @param seq
         * @throws SAXException
         */
        @Override
        public void characters(CharSequence seq) throws SAXException {
            if (inCallback) {
                super.characters(seq);
                return;
            }
            String s = seq.toString();
            try {
                inCallback = true;
                classifyText(s, builder, this, null, callback);
            } catch (XPathException e) {
                throw new SAXException(e.getMessage(), e);
            } finally {
                inCallback = false;
            }
        }

        /**
         *
         * @param ch
         * @param start
         * @param len
         * @throws SAXException
         */
        @Override
        public void characters(char[] ch, int start, int len) throws SAXException {
            if (inCallback) {
                super.characters(ch, start, len);
                return;
            }
            String s = new String(ch, start, len);
            try {
                inCallback = true;
                classifyText(s, builder, this, null, callback);
            } catch (XPathException e) {
                throw new SAXException(e.getMessage(), e);
            } finally {
                inCallback = false;
            }
        }
    }
}
