package ru.pikatv.domain

interface PikabuRepository {
    /** Discard tokens and user-specific comment snapshots before reading under changed cookies. */
    fun invalidateSession() {}
    suspend fun getFeed(feed: FeedType, page: Int = 1): FeedPage
    suspend fun getPost(url: String): Post
    suspend fun getComments(postUrl: String): CommentsPage
    suspend fun getCommentsByIds(postUrl: String, ids: List<String>): List<Comment>
    suspend fun getBranch(postUrl: String, commentId: String): List<Comment>
    /** Votes are the only writes to the site: [value] is 1, -1 or 0 to withdraw. They require a signed-in session. */
    /** Reactions a user may add to a post or comment. */
    val reactionChoices: List<Reaction> get() = emptyList()
    suspend fun react(post: Post, reaction: Reaction, on: Boolean)
    suspend fun reactComment(postUrl: String, comment: Comment, reaction: Reaction, on: Boolean)
    suspend fun vote(post: Post, value: Int)
    suspend fun voteComment(postUrl: String, comment: Comment, value: Int)
}
