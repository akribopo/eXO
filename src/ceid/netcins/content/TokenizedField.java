package ceid.netcins.content;

import java.io.Serializable;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.Random;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * This ContentField type contains a set of "terms" (String values) and the 
 * corresponding "term frequencies" (tf), obtained from a source such as a file.
 * 
 * @author Andreas Loupasakis
 */
public class TokenizedField extends ContentField implements Serializable {

	private static final long serialVersionUID = 3889036995034671146L;

	// Terms are provided in sorted order;
	private Hashtable<String, Integer> termFreq;

	private static final Random rng = new Random(System.currentTimeMillis());

	// TODO : some error checking
	public TokenizedField(String name, TreeMap<String, Integer> tfm, boolean isPublic) {
		super(name, isPublic);

		// Allocate the necessary size and transfer the terms and tfs
		termFreq = new Hashtable<String, Integer>();
		termFreq.putAll(tfm);
	}

	public TokenizedField(String name, TreeMap<String, Integer> tfm) {
		this(name, tfm, ContentField.defaultAccessMode);
	}

	/**
	 * Constructor which must be used if we dont have term frequencies
	 * 
	 * @param name
	 * @param tfm
	 */
	public TokenizedField(String name, TreeSet<String> tfm, boolean isPublic) {
		super(name, isPublic);

		// Allocate the necessary size and transfer the terms
		termFreq = new Hashtable<String, Integer>();
		if (tfm != null)
			for (String term : tfm) {
				Integer freq = (termFreq.containsKey(term) ? termFreq.get(term) : 0);
				termFreq.put(term, freq);
			}
	}

	public TokenizedField(String name, TreeSet<String> tfm) {
		this(name, tfm, ContentField.defaultAccessMode);
	}

	public void merge(TokenizedField tkf) {
		if (tkf.termFreq == null || tkf.termFreq.size() == 0)
			return;
		if (termFreq == null) {
			termFreq = new Hashtable<String, Integer>(tkf.termFreq);
			return;
		}
		Iterator<String> keys = tkf.termFreq.keySet().iterator();
		Iterator<Integer> values = tkf.termFreq.values().iterator();
		while (keys.hasNext()) {
			String otherKey = keys.next();
			Integer otherValue = values.next();
			addTerm(otherKey, otherValue);
		}
	}

	public String[] getTerms() {
		return (String[])termFreq.keySet().toArray();
	}

	public Integer[] getTF() {
		return (Integer[])termFreq.values().toArray();
	}

	public void addTerm(String term) {
		addTerm(term, 1);
	}

	public void addTerm(String term, Integer freq) {
		if (freq == null || freq < 0)
			throw new RuntimeException("Negative frequency");
		Integer oldFreq = termFreq.get(term);
		if (oldFreq == null)
			oldFreq = Integer.valueOf(0);
		termFreq.put(term, freq + oldFreq);
	}

	public String randomTerm() {
		if (termFreq.isEmpty())
			return null;
		int num = rng.nextInt(termFreq.size());
		return (String)(termFreq.keySet().toArray()[num]);
	}

	/* (non-Javadoc)
	 * @see ceid.netcins.content.ContentField#size()
	 */
	public int size() {
		int sum = super.size();
		for (String s : termFreq.keySet()) {
			sum += s.getBytes().length + 4; // 4 bytes for the term frequencies
		}
		return sum;
	}

	/* (non-Javadoc)
	 * @see java.lang.Object#toString()
	 */
	public String toString() {
		StringBuffer buffer = new StringBuffer();
		buffer.append("Tokenized Field " + name);
		Iterator<String> keys = termFreq.keySet().iterator();
		Iterator<Integer> values = termFreq.values().iterator();
		while (keys.hasNext()) {
			String term = keys.next();
			Integer freq = values.next();
			buffer.append("\n Term: " + term);
			if (freq != 0)
				buffer.append(", TF : " + freq);
		}
		buffer.append("\n");
		return buffer.toString();
	}

	public String toStringWithoutTF() {
		StringBuffer buffer = new StringBuffer();
		buffer.append("Tokenized Field " + name);
		Iterator<String> keys = termFreq.keySet().iterator();
		while (keys.hasNext()) {
			buffer.append("\n Term: " + keys.next());
		}
		buffer.append("\n");
		return buffer.toString();
	}
}
