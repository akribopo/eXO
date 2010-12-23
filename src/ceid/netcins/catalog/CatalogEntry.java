package ceid.netcins.catalog;

import java.io.Serializable;

import rice.p2p.commonapi.Id;

/**
 * This is a sum of data in a Catalog row!
 * 
 * @author Andreas Loupasakis
 * @version 1.0
 * 
 */
@SuppressWarnings("rawtypes")
public abstract class CatalogEntry implements Serializable, Comparable {

	private static final long serialVersionUID = -6930057763768157893L;
	// User identifier (or node Identifier)
	private Id uid;

	public CatalogEntry(Id uid) {
		this.uid = uid;
	}

	@Override
	public int hashCode() {
		return uid.hashCode();
	}
	
	/**
	 * Used to compare two entries. Two entries are the same if: they have the
	 * same UID (and the same SHA-1 checksum if it is content entry)
	 * 
	 * @param o
	 *            DESCRIBE THE PARAMETER
	 * @return DESCRIBE THE RETURN VALUE
	 */
	@Override
	public boolean equals(Object o) {
		return (o instanceof CatalogEntry &&
				uid.equals(((CatalogEntry)o).uid));
	}

	/**
	 * Getter for the uid of User
	 * 
	 * @return the uid
	 */
	public Id getUID() {
		return this.uid;
	}

	@Override
	public String toString() {
		StringBuffer buf = new StringBuffer();
		buf.append("Catalog Entry : [UID] = ");
		buf.append(this.uid.toString());
		return buf.toString();
	}

	/**
	 * Implementation for Comparable interface
	 * 
	 * @param arg0
	 * @return
	 */
	public int compareTo(Object arg0) {
		if (!(arg0 instanceof CatalogEntry)) {
			throw new ClassCastException();
		}
		CatalogEntry ce = (CatalogEntry) arg0;

		if (this.equals(arg0))
			return 0;
		else {
			return this.getUID().compareTo(ce.getUID());
		}
	}
	
	
	/**
	 * 
	 * @return A sum of the UserCatalogEntry data in bytes
	 */
	public abstract double computeTotalBytes();
}
