package ceid.netcins.exo.messages;

import ceid.netcins.exo.catalog.Catalog;
import ceid.netcins.exo.catalog.CatalogEntry;
import ceid.netcins.exo.catalog.ContentCatalogEntry;
import ceid.netcins.exo.catalog.URLCatalogEntry;
import rice.p2p.commonapi.Id;
import rice.p2p.past.ContentHashPastContent;
import rice.p2p.past.PastContent;
import rice.p2p.past.PastException;

import java.io.Serializable;
import java.util.Hashtable;

/**
 * This class represents the Protocol Data Unit (PDU) of the insert message of
 * our DHTService. This class will include mostly CatalogEntries or data for the
 * update of the Catalog entries.
 *
 * @author <a href="mailto:loupasak@ceid.upatras.gr">Andreas Loupasakis</a>
 * @author <a href="mailto:ntarmos@cs.uoi.gr">Nikos Ntarmos</a>
 * @author <a href="mailto:peter@ceid.upatras.gr">Peter Triantafillou</a>
 *         <p/>
 *         "eXO: Decentralized Autonomous Scalable Social Networking"
 *         Proc. 5th Biennial Conf. on Innovative Data Systems Research (CIDR),
 *         January 9-12, 2011, Asilomar, California, USA.
 * @version 1.0
 */
public class InsertPDU extends ContentHashPastContent implements Serializable {

    private static final long serialVersionUID = -1904494570321601347L;

    public static enum CatalogType {USER, CONTENT, URL}

    ;
    private CatalogEntry additions, deletions; // The packet data
    private CatalogType type;

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();
        builder.append("{ InsertPDU: ");
        switch (type) {
            case CONTENT:
                builder.append("[CONTENT]");
                break;
            case URL:
                builder.append("[URL]");
                break;
            case USER:
                builder.append("[USER]");
                break;
        }
        builder.append(" +");
        builder.append(additions);
        builder.append(" -");
        builder.append(deletions);
        builder.append(" }");
        return builder.toString();
    }

    public InsertPDU(Id tid, CatalogEntry add, CatalogEntry del) {
        super(tid);
        if ((add != null && del != null) &&
                !(add.getClass().equals(del.getClass()) &&
                        add.getUID().equals(del.getUID())
                )
                )
            throw new RuntimeException("Attempting to create an InsertPDU with non-matching entry types or UIDs");
        CatalogEntry nonNull = (add != null) ? add : del;
        if (nonNull instanceof URLCatalogEntry)
            type = CatalogType.URL;
        else if (nonNull instanceof ContentCatalogEntry)
            type = CatalogType.CONTENT;
        else
            type = CatalogType.USER;
        this.additions = add;
        this.deletions = del;
    }

    /**
     * States if this content object is mutable. Mutable objects are not subject
     * to dynamic caching in Past.
     *
     * @return true if this object is mutable, else false
     */
    @Override
    public boolean isMutable() {
        return true;
    }

    /**
     * Checks if an insert operation should be allowed. Invoked when a Past node
     * receives an insert request and it is a replica root for the id; invoked
     * on the object to be inserted. This method determines the effect of an
     * insert operation on an object that already exists: it computes the new
     * value of the stored object, as a function of the new and the existing
     * object.
     * <p/>
     * This is an overriden version of this method. It is called in the indexing
     * process when a new content object has arrived to the destination Catalog
     * node. The Catalog is checked to ensure that no exactly same CatalogEntry
     * exists. If it is so, then the new entry is added to the current Catalog
     * of the specific TID which is being indexed.
     *
     * @param id              the key identifying the object
     * @param existingContent DESCRIBE THE PARAMETER
     * @return null, if the operation is not allowed; else, the new object to be
     *         stored on the local node.
     * @throws PastException DESCRIBE THE EXCEPTION
     */

    @SuppressWarnings("unchecked")
    @Override
    public PastContent checkInsert(Id id, PastContent existingContent)
            throws PastException {
        if (additions == null && deletions == null)
            return existingContent;

        // only allow correct content hash key
        if (!id.equals(getId())) {
            throw new PastException(
                    "ContentHashPastContent: can't insert, content hash incorrect");
        }

        if (existingContent != null && !(existingContent instanceof Catalog))
            throw new PastException(
                    "Catalog : can't insert, existing object for the TID("
                            + this.myId + ") is of unknown class type");

        if (existingContent == null) {
            // There is no Catalog for this TID! Let's create one :-)
            Catalog c = new Catalog(id);
            CatalogEntry finalEntry = null;
            if (additions != null) {
                finalEntry = additions;
                finalEntry.subtract(deletions);
                c.addCatalogEntry(finalEntry);
            }
            return c;
        }

        synchronized (existingContent) {
            // Update existing Catalog entry
            Catalog catalog = (Catalog) existingContent;

            // Here is the main processing of new data
            @SuppressWarnings("rawtypes")
            Hashtable catalogEntries = null;
            switch (type) {
                case USER:
                    catalogEntries = catalog.getUserCatalogEntries();
                    break;
                case CONTENT:
                    catalogEntries = catalog.getContentCatalogEntries();
                    break;
                case URL:
                    catalogEntries = catalog.getURLCatalogEntries();
                    break;
            }

            CatalogEntry finalEntry = (CatalogEntry) catalogEntries.get((additions != null) ? additions.getUID() : deletions.getUID());
            if (finalEntry == null) {
                finalEntry = additions;
                if (finalEntry != null)
                    finalEntry.subtract(deletions);
            } else {
                finalEntry.add(additions);
                finalEntry.subtract(deletions);
            }
            if (finalEntry != null)
                catalogEntries.put(finalEntry.getUID(), finalEntry);
            return existingContent; // The same reference as catalog!
        }
    }
}
