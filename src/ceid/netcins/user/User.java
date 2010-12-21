package ceid.netcins.user;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Vector;

import rice.p2p.commonapi.Id;
import ceid.netcins.catalog.ContentCatalogEntry;
import ceid.netcins.catalog.SocialCatalog;
import ceid.netcins.catalog.UserCatalogEntry;
import ceid.netcins.content.ContentField;
import ceid.netcins.content.ContentProfile;
import ceid.netcins.content.TermField;
import ceid.netcins.messages.QueryPDU;
import ceid.netcins.social.SocialBookMark;
//import ceid.netcins.social.SocialLink;
import ceid.netcins.social.TagCloud;

/**
 * This class represents a User entity. User includes all the necessary
 * information about a user.
 * 
 * @author Andreas Loupasakis
 */
public class User {
	public static final String UsernameTag = "Username";
	public static final String ResourceTag = "Resource";
	public static final String NotAvailableTag = "<N/A>";

	// User unique identifier created by SHA-1 hash function
	private Id uid;

	// User nick(screen) name in the network. This name will be indexed as a
	// field in the userProfile!
	private String username;

	private String resourceName;

	// This is the set of "terms" that describe the user.
	private ContentProfile userProfile;

	// The list of friends' UIDs
	private List<Friend> friends;

	// Friendship requests pending to be confirmed by user
	private Vector<FriendRequest> pendingIncomingFReq;

	// Friendship requests that user waits to be approved by his candidate friends
	private Vector<Id> pendingOutgoingFReq;

	// Map of shared files with their corresponding SHA-1 checksums.
	// TIP : SHA-1 checksum is returned by "libextractor", so we need to use
	// buildId(String) to obtain the Id instance.
	private Map<Id, File> sharedContent;

	// Checksum or synonym Id with the the corresponding content profile
	// TODO : This should be later be merged with the sharedContent!
	private Map<Id, ContentProfile> sharedContentProfile;

	// The content Ids (checksums) mapped with their corresponding TagCloud.
	// TAGEE's PERSPECTIVE (owner of content)
	private Map<Id, TagCloud> contentTagClouds;

	private Map<Id, TagCloud> userTagClouds;
	
	// The tag Ids mapped with their corresponding Content Profiles of contents,
	// which have been tagged by this user.
	// TAGER's PERSPECTIVE (non-owner of content)
	// alternatively Map<Id,SocialCatalog>
	private Map<String, SocialCatalog> invertedTagContentList;

	// Bookmarks : URLBookMarks etc.
	// Maps Bookmark Id (bid) to the set of tags, name, description etc.
	private Map<Id, SocialBookMark> bookMarks;

	// TODO: Implement this, Describe design etc.
	//private Map<Id, SocialLink> sociallinks;

	/**
	 * Constructor of user entity
	 * 
	 * @param uid
	 */
	public User(Id uid) {
		this(uid, null, (ContentProfile)null);
	}

	/**
	 * Constructor of user entity
	 * 
	 * @param uid
	 * @param username
	 */
	public User(Id uid, String username) {
		this(uid, username, (ContentProfile)null);
	}

	/**
	 * Constructor of user entity
	 * 
	 * @param uid
	 * @param username
	 * @param resourceName
	 */
	public User(Id uid, String username, String resourceName) {
		this(uid, username, resourceName, null, null);
	}

	/**
	 * Constructor of user entity
	 * 
	 * @param username
	 * @param userProfile
	 */
	public User(Id uid, String username, ContentProfile userProfile) {
		this(uid, username, null, userProfile, null);
	}

	/**
	 * Constructor of user entity
	 * 
	 * @param username
	 * @param userProfile
	 * @param friends
	 */
	public User(Id uid, String username, String resourceName, ContentProfile userProfile,
			List<Friend> friends) {
		this.uid = uid;
		this.username = username;
		this.resourceName = resourceName;
		setUserProfile(userProfile);

		if (friends == null)
			this.friends = new Vector<Friend>();
		else
			this.friends = friends;
		this.pendingIncomingFReq = new Vector<FriendRequest>();
		this.pendingOutgoingFReq = new Vector<Id>();
		this.sharedContent = new HashMap<Id, File>();
		this.sharedContentProfile = new HashMap<Id, ContentProfile>();
		this.bookMarks = new HashMap<Id, SocialBookMark>();
		this.contentTagClouds = new HashMap<Id, TagCloud>();
		this.invertedTagContentList = new HashMap<String, SocialCatalog>();
	}

	/**
	 * Helper to get the proper entries corresponding to the query type issued
	 * by the user.
	 * 
	 * @param type One of the types defined in QueryPDU
	 * @return Return the corresponding vector of catalog entries.
	 */
	@SuppressWarnings({ "unchecked", "rawtypes" })
	public Vector getCatalogEntriesForQueryType(int type, Id requester){
		Vector v = null;
		switch(type){
			case QueryPDU.CONTENTQUERY:
				v = this.wrapContentToCatalogEntries();
				break;
			case QueryPDU.CONTENT_ENHANCEDQUERY:
				v = this.wrapContentToCatalogEntries(isFriend(requester)?true:
					false);
				break;
			case QueryPDU.HYBRIDQUERY:
				// TODO: Implement this
				break;
			case QueryPDU.HYBRID_ENHANCEDQUERY:
				// TODO: Implement this 
				break;
			case QueryPDU.USERQUERY:
				v = new Vector<UserCatalogEntry>();
				v.add(this.wrapUserProfileToCatalogEntry(
						isFriend(requester)?true:false));
				break;
			case QueryPDU.USER_ENHANCEDQUERY:
				v = new Vector<UserCatalogEntry>();
				v.add(this.wrapUserProfileToCatalogEntry(
						isFriend(requester)?true:false));
				break;
		}
		return v; 
	}

	/**
	 * Helper to wrap the user profile to UserCatalogEntry.
	 * This is useful for similarity processing. E.g. to be included in a 
	 * SimilarityRequest.
	 * 
	 * @param completeUserProfile Choose if we will include the public or the 
	 * complete version of the user profile (if the queried user is friend ...)
	 * @return The wrapped user profile.
	 */
	public UserCatalogEntry wrapUserProfileToCatalogEntry(
			boolean completeUserProfile){
		return new UserCatalogEntry(this.uid, completeUserProfile?
				this.getCompleteUserProfile():
					this.getPublicUserProfile());
	}

	public Vector<ContentCatalogEntry> wrapContentToCatalogEntries(){
		return this.wrapContentToCatalogEntries(false, false);
	}

	public Vector<ContentCatalogEntry> wrapContentToCatalogEntries(
			boolean completeUserProfile){
		return this.wrapContentToCatalogEntries(true, completeUserProfile);
	}

	/**
	 * Helper to wrap the shared content profiles to ContentCatalogEntries.
	 * This is useful to form a list for similarity processing. E.g. to include
	 * in a SimilarityRequest. 
	 * 
	 * @param includeUserProfile Flag to denote if user profile is needed to 
	 * be wrapped too.
	 * @param completeUserProfile In case we want to include the user profile
	 * choose if we will include the public or the complete version.
	 * @return A vector with all the content profiles wrapped to catalog entries.
	 */
	public Vector<ContentCatalogEntry> wrapContentToCatalogEntries(
			boolean includeUserProfile,	boolean completeUserProfile){
		Vector<ContentCatalogEntry> v = new Vector<ContentCatalogEntry>();
		for(Id id : this.sharedContentProfile.keySet()){
			v.add(new ContentCatalogEntry(this.uid,
					sharedContentProfile.get(id), 
					includeUserProfile?
							(completeUserProfile?
									this.getCompleteUserProfile():
										this.getPublicUserProfile()): null));
		}
		return v;
	}

	/**
	 * Given an Id, determine if the its owner belongs to user friends. 
	 * 
	 * @param user The id of the checking user
	 * @return True if he is a friend, false if he is not.
	 */
	public boolean isFriend(Id user){
		for(Friend f : this.friends){
			if(f.getUID().equals(user))
				return true;
		}
		return false;
	}

	public void setUserProfile(ContentProfile userProfile) {
		if (userProfile != null)
			this.userProfile = new ContentProfile(userProfile);
		else
			this.userProfile = new ContentProfile();

		boolean foundUName = false, foundRName = false;
		List<ContentField> fields = this.userProfile.getAllFields();
		if (fields != null) {
			for (ContentField cf : fields) {
				if (cf instanceof TermField) {
					if(((TermField)cf).getFieldName().equals(UsernameTag))
						foundUName = true;
					else if (((TermField)cf).getFieldName().equals(ResourceTag))
						foundRName = true;
				}
				if (foundUName && foundRName)
					break;
			}
		}
		if (!foundUName) {
			if (username != null)
				this.userProfile.add(new TermField(UsernameTag, username, true));
			else
				this.userProfile.add(new TermField(UsernameTag, NotAvailableTag, true));
		}
		if (!foundRName) { 
			if (resourceName != null)
				this.userProfile.add(new TermField(ResourceTag, resourceName, true));
			else
				this.userProfile.add(new TermField(ResourceTag, NotAvailableTag, true));
		}
	}

	public void setFriends(List<Friend> friends) {
		this.friends = friends;
	}

	public ContentProfile getCompleteUserProfile() {
		return userProfile;
	}

	public ContentProfile getPublicUserProfile() {
		return userProfile.getPublicPart();
	}

	public List<Friend> getFriends() {
		return friends;
	}

	public Id getUID() {
		return uid;
	}

	public String getUsername() {
		return username;
	}

	public String getResourceName() {
		return resourceName;
	}

	public Vector<FriendRequest> getPendingIncomingFReq() {
		return pendingIncomingFReq;
	}

	public Vector<Id> getPendingOutgoingFReq() {
		return pendingOutgoingFReq;
	}

	public Map<Id, File> getSharedContent() {
		return sharedContent;
	}

	public Map<Id, ContentProfile> getSharedContentProfile() {
		return sharedContentProfile;
	}

	public Map<Id, SocialBookMark> getBookMarks() {
		return bookMarks;
	}

	public Map<Id, TagCloud> getContentTagClouds() {
		return contentTagClouds;
	}

	public Map<Id, TagCloud> getUserTagClouds() {
		return userTagClouds;
	}

	public Map<String, SocialCatalog> getTagContentList() {
		return invertedTagContentList;
	}

	/**
	 * Add a friend's uid in the friend list
	 * 
	 * @param friend
	 */
	public void addFriend(Friend friend) {
		friends.add(friend);
	}

	public void addPendingIncomingFReq(FriendRequest freq) {
		pendingIncomingFReq.add(freq);
	}

	public void addPendingOutgoingFReq(Id uid) {
		pendingOutgoingFReq.add(uid);
	}

	public void addSharedContent(Id checksum, File file) {
		sharedContent.put(checksum, file);
	}

	public void addSharedContentProfile(Id checksum, ContentProfile cp) {
		sharedContentProfile.put(checksum, cp);
	}

	public void addBookMark(Id bid, SocialBookMark sbm) {
		bookMarks.put(bid, sbm);
	}

	public void addContentTagCloud(Id cid, TagCloud tc) {
		contentTagClouds.put(cid, tc);
	}

	public void addTagContentList(String tag, SocialCatalog cat) {
		invertedTagContentList.put(tag, cat);
	}

	public void removeFriend(Friend friend) {
		friends.remove(friend);
	}

	public void removePendingIncomingFReq(FriendRequest freq) {
		pendingIncomingFReq.remove(freq);
	}

	public void removePendingOutgoingFReq(Id uid) {
		pendingOutgoingFReq.remove(uid);
	}

	public void removeSharedContent(Id checksum) {
		sharedContent.remove(checksum);
	}

	public void removeSharedContentProfile(Id checksum) {
		sharedContentProfile.remove(checksum);
	}

	public void removeBookMark(Id bid) {
		bookMarks.remove(bid);
	}

	public void removeContentTagCloud(Id cid) {
		contentTagClouds.remove(cid);
	}

	public void removeTagContentList(String tag) {
		invertedTagContentList.remove(tag);
	}
}
