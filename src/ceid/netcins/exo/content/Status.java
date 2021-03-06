package ceid.netcins.exo.content;

import ceid.netcins.exo.frontend.json.StatusJSONConvertor;

import java.io.Serializable;
import java.util.Date;

/**
 *
 */

public class Status extends ContentField implements Serializable, Comparable<Status> {
    public final static String STATUS_ID = StatusJSONConvertor.StatusTag;

    /**
     *
     */
    private static final long serialVersionUID = 1263562213103678794L;
    String statusText;
    Date date;

    public Status(String statusText, boolean isPublic) {
        super(STATUS_ID, isPublic);
        this.statusText = statusText;
        this.date = new Date(System.currentTimeMillis());
    }

    public Status(String fieldData) {
        super(STATUS_ID);
        this.statusText = fieldData;
        this.date = new Date(System.currentTimeMillis());
    }

    public Status(String statusText, long dateLong, boolean isPublic) {
        super(STATUS_ID, isPublic);
        this.statusText = statusText;
        this.date = new Date(dateLong);
    }

    public String getStatusText() {
        return this.statusText;
    }

    public Date getDate() {
        return this.date;
    }

    /* (non-Javadoc)
      * @see java.lang.Object#toString()
      */
    public String toString() {
        //return statusText;
        return "TF{ \"" + name + "\" : { \"" + statusText + "\" , " + (isPublic ? "public" : "private") + " }}";
    }

    @Override
    public boolean equals(Object o) {
        return (o instanceof Status &&
                super.equals((ContentField) o) &&
                statusText.equals(((Status) o).getStatusText())
                && date.equals(((Status) o).getDate()));
    }

    @Override
    public int hashCode() {
        return super.hashCode() + statusText.hashCode() + date.hashCode();
    }

    @Override
    public int compareTo(final Status thatStatus) {
        return (int) (this.date.getTime() - thatStatus.getDate().getTime());
    }
}
