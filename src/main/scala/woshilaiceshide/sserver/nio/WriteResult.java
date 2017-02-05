package woshilaiceshide.sserver.nio;

public final class WriteResult {
	private WriteResult() {
	}

	public static final WriteResult WR_UNKNOWN = new WriteResult();
	// the target is put in the buffer successfully.
	public static final WriteResult WR_OK = new WriteResult();
	public static final WriteResult WR_FAILED_BECAUSE_TOO_MANY_WRITES_EXISTED = new WriteResult();
	public static final WriteResult WR_FAILED_BECAUSE_CHANNEL_CLOSED = new WriteResult();

	// commented. this value makes no sense in practice,
	// but make things more complicated.
	public static final WriteResult WR_FAILED_BECAUSE_EMPTY_CONTENT_TO_BE_WRITTEN = new WriteResult();
	// written succeeded,
	// but the inner buffer pool is full(overflowed in fact),
	// which will make the next writing failed definitely.
	// wait for 'def channelWritable(...)' if this value encountered.
	public static final WriteResult WR_OK_BUT_OVERFLOWED = new WriteResult();

}