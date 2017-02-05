package woshilaiceshide.sserver.nio;

public class JavaAccelerator {

	final static class TryWrite {
		WriteResult result;
		boolean pend;
	}

	public static final int CHANNEL_UNKNOWN = -1;
	public static final int CHANNEL_NORMAL = 0;
	public static final int CHANNEL_CLOSING_GRACEFULLY = 1;
	public static final int CHANNEL_CLOSING_RIGHT_NOW = 2;
	public static final int CHANNEL_CLOSED = 3;

	private static void write_immediately(NioSocketReaderWriter.MyChannelWrapper wrapper) {
		int written = wrapper.writing();
		if ((written & 0x2) != 0x2)
			wrapper.set_op_write();
	}

	public static void check_io(NioSocketReaderWriter.MyChannelWrapper wrapper) {

		scala.Enumeration.Value cause = null;
		scala.Option<?> attachment = scala.Option.empty();
		boolean try_write = false;
		boolean should_close = false;

		synchronized (wrapper) {
			cause = wrapper.closed_cause();
			attachment = wrapper.attachment_for_closed();

			wrapper.already_pended_$eq(false);
			int status = wrapper.status();

			if (status == CHANNEL_NORMAL && null != wrapper.writes()) {
				try_write = true;
			} else if (status == CHANNEL_CLOSED) {
				should_close = false;
			} else if (status == CHANNEL_CLOSING_RIGHT_NOW) {
				SelectorRunner.safe_close(wrapper.channel());
				wrapper.writes_$eq(null);
				wrapper.status_$eq(CHANNEL_CLOSED);
				should_close = true;
			} else if (status == CHANNEL_CLOSING_GRACEFULLY && null == wrapper.writes()) {
				SelectorRunner.safe_close(wrapper.channel());
				wrapper.status_$eq(CHANNEL_CLOSED);
				should_close = true;
			} else if (status == CHANNEL_CLOSING_GRACEFULLY) {
				// closeIfFailed { setOpWrite() }
				try {
					wrapper.just_op_write_if_needed_or_no_op();
					// TODO tell the peer not to send data??? is it harmful
					// to the peer if the peer can not response correctly?
					wrapper.channel().shutdownInput();
					should_close = false;
				} catch (Throwable thread) {
					SelectorRunner.safe_close(wrapper.channel());
					wrapper.status_$eq(CHANNEL_CLOSED);
					should_close = true;
				}
			} else if (status == CHANNEL_NORMAL) {
				// now 'null == wrapper.writes()' is true
				// closeIfFailed { clearOpWrite() }
				should_close = false;
			} else {
				should_close = false;
			}
		}
		if (try_write) {
			try {
				write_immediately(wrapper);
				should_close = false;
			} catch (Throwable thread) {
				SelectorRunner.safe_close(wrapper.channel());
				// wrapper.status_$eq(CHANNEL_CLOSED);
				should_close = true;
			}
		}
		// close outside, not in the "synchronization". keep locks clean.
		if (should_close) {
			try {
				if (null != wrapper.handler()) {
					wrapper.handler().channelClosed(wrapper, cause, attachment);
					wrapper.handler_$eq(null);
				}
				wrapper.key().cancel();
			} catch (Throwable throwable) {
				SelectorRunner.log().debug("failed when close channel", throwable);
			}
		}

	}

}
