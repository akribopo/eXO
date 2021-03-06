package ceid.netcins.exo.social;

import ceid.netcins.exo.content.ContentField;

import java.io.Serializable;
import java.util.Hashtable;
import java.util.Iterator;

/**
 * This is a set of social terms/tags with a corresponding weight = TF.
 * The tag cloud consists of terms contributed by users other than the owner.
 *
 * @author <a href="mailto:loupasak@ceid.upatras.gr">Andreas Loupasakis</a>
 * @author <a href="mailto:ntarmos@cs.uoi.gr">Nikos Ntarmos</a>
 * @author <a href="mailto:peter@ceid.upatras.gr">Peter Triantafillou</a>
 *         <p/>
 *         "eXO: Decentralized Autonomous Scalable Social Networking"
 *         Proc. 5th Biennial Conf. on Innovative Data Systems Research (CIDR),
 *         January 9-12, 2011, Asilomar, California, USA.
 */
public class TagCloud implements Serializable {
    private static final long serialVersionUID = -1110912254142511274L;
    // Term frequency map
    private Hashtable<ContentField, Integer> tfm;

    /**
     * Constructor
     *
     * @param tfm
     */
    public TagCloud(Hashtable<ContentField, Integer> tfm) {
        this.tfm = tfm;
    }

    /**
     * Constructor
     */
    public TagCloud() {
        this.tfm = new Hashtable<ContentField, Integer>();
    }

    /**
     * Getter for tfm
     *
     * @return
     */
    public Hashtable<ContentField, Integer> getTagTFMap() {
        return tfm;
    }

    /**
     * Insert a new tag or add +1 to TF
     *
     * @param tag
     */
    public void addTagTFMap(ContentField tag) {

        // Increase the TF
        if (tfm.containsKey(tag)) {
            int num = tfm.get(tag);
            tfm.put(tag, (num + 1));
        } else {
            tfm.put(tag, 1);
        }
    }

    /**
     * String representation of TagCloud
     *
     * @return
     */
    @Override
    public String toString() {
        StringBuffer buf = new StringBuffer();
        buf.append("TagCloud : [");
        Iterator<ContentField> it = tfm.keySet().iterator();
        ContentField tag;
        while (it.hasNext()) {
            tag = it.next();
            buf.append("{ tag : " + tag + ", freq : " + tfm.get(tag) + " }"
                    + (it.hasNext() ? "," : ""));
        }
        buf.append("]");
        return buf.toString();
    }
}
