import edu.stanford.nlp.ling.HasWord;
import edu.stanford.nlp.ling.Sentence;
import edu.stanford.nlp.parser.lexparser.LexicalizedParser;
import edu.stanford.nlp.trees.Tree;
import edu.stanford.nlp.trees.TreebankLanguagePack;
import edu.stanford.nlp.trees.GrammaticalStructure;
import edu.stanford.nlp.trees.GrammaticalStructureFactory;

import java.io.*;
import java.util.List;

public class TextAnalyzer {

    public enum AnalysisType {
        POS,
        CONSTITUENCY,
        DEPENDENCY
    }

    private static final String PCFG_MODEL = "edu/stanford/nlp/models/lexparser/englishPCFG.ser.gz";
    private final LexicalizedParser parser;

    public TextAnalyzer() {
        parser = LexicalizedParser.loadModel(PCFG_MODEL);
    }

    public void analyzeFile(File input, File output, AnalysisType type) throws Exception {
        try (BufferedReader br = new BufferedReader(new FileReader(input));
             BufferedWriter bw = new BufferedWriter(new FileWriter(output))) {

            String line;
            while ((line = br.readLine()) != null) {

                if (line.trim().isEmpty()) {
                    bw.write("\n");
                    continue;
                }

                switch (type) {
                    case POS:
                        bw.write(processPOS(line));
                        break;

                    case CONSTITUENCY:
                        bw.write(processConstituency(line));
                        break;

                    case DEPENDENCY:
                        bw.write(processDependency(line));
                        break;
                }

                bw.write("\n");
            }
        }
    }

    private String processPOS(String text) {
        List<HasWord> sentence = Sentence.toWordList(text.split("\\s+"));
        Tree parse = parser.apply(sentence);

        // Leaves with POS tags
        StringBuilder sb = new StringBuilder();
        for (Tree leaf : parse.getLeaves()) {
            Tree parent = leaf.parent(parse);
            sb.append(leaf.value()).append("_").append(parent.label()).append(" ");
        }
        return sb.toString().trim();
    }

    private String processConstituency(String text) {
        List<HasWord> sentence = Sentence.toWordList(text.split("\\s+"));
        Tree parse = parser.apply(sentence);
        return parse.toString();
    }

    private String processDependency(String text) {
        List<HasWord> sentence = Sentence.toWordList(text.split("\\s+"));
        Tree parse = parser.apply(sentence);

        TreebankLanguagePack tlp = parser.treebankLanguagePack();
        GrammaticalStructureFactory gsf = tlp.grammaticalStructureFactory();
        GrammaticalStructure gs = gsf.newGrammaticalStructure(parse);

        return gs.typedDependencies().toString();
    }

}
