package ceid.netcins.json;

import java.util.Iterator;
import java.util.Map;

import rice.p2p.commonapi.Id;

import ceid.netcins.content.ContentProfile;
import ceid.netcins.content.StoredField;
import ceid.netcins.content.TermField;
import ceid.netcins.content.TokenizedField;
import ceid.netcins.social.TagCloud;

public class Json extends org.eclipse.jetty.util.ajax.JSON {
	private static Json __default = new Json();

	public Json() {
		registerConvertor(TermField.class, new TermFieldJSONConvertor());
		registerConvertor(StoredField.class, new StoredFieldJSONConvertor());
		registerConvertor(TokenizedField.class, new TokenizedFieldJSONConvertor());
		registerConvertor(ContentProfile.class, new ContentProfileJSONConvertor());
		registerConvertor(TagCloud.class, new TagCloudJSONConvertor());
		registerConvertor(Id.class, new IdJSONConvertor());
	}

	/* (non-Javadoc)
	 * @see org.eclipse.jetty.util.ajax.JSON#appendMap(java.lang.StringBuffer, java.util.Map)
	 */
	@Override
	@SuppressWarnings("rawtypes")
	public void appendMap(StringBuffer buffer, Map object) {
		// Overriding Jetty {@link org.eclipse.jetty.util.ajax.JSON}'s appendMap(...) 
		// method to let it use Convertors registered for key values.
		// TODO: should probably also submit upsteram to Jetty
		if (object==null) {
			appendNull(buffer);
			return;
		}

		buffer.append('{');
		Iterator iter=object.entrySet().iterator();
		while (iter.hasNext()) {
			Map.Entry entry=(Map.Entry)iter.next();
			Convertor convertor=getConvertor(entry.getKey().getClass());
			if (convertor != null)
				appendJSON(buffer,convertor,entry.getKey());
			else
				appendString(buffer,entry.getKey().toString());
			buffer.append(':');
			append(buffer,entry.getValue());
			if (iter.hasNext())
				buffer.append(',');
		}
		buffer.append('}');
	}

	@SuppressWarnings("rawtypes")
	public static String toString(Map object)
	{
		StringBuffer buffer=new StringBuffer(__default.getStringBufferSize());
		synchronized (buffer)
		{
			__default.appendMap(buffer,object);
			return buffer.toString();
		}
	}
}