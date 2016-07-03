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

	public static void check_io(NioSocketReaderWriter.MyChannelWrapper wrapper) {

		scala.Enumeration.Value cause = null;
		scala.Option<?> attachment = scala.Option.empty();
		boolean generate_written_event = false;
		boolean should_close = false;

		synchronized (wrapper) {
			generate_written_event = wrapper.should_generate_written_event();
			wrapper.should_generate_written_event_$eq(false);
			cause = wrapper.closed_cause();
			attachment = wrapper.attachment_for_closed();

			wrapper.already_pended_$eq(false);
			int status = wrapper.status();

			if (status == CHANNEL_NORMAL && null != wrapper.writes()) {
				try {
					wrapper.set_op_write();
					should_close = false;
				} catch (Throwable thread) {
					SelectorRunner.safe_close(wrapper.channel());
					wrapper.status_$eq(CHANNEL_CLOSED);
					should_close = true;
				}
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
		} else {
			if (generate_written_event) {
				if (null != wrapper.handler()) {
					// nothing to do with oldHandler
					wrapper.handler_$eq(wrapper.handler().writtenHappened(wrapper));
				}
			}
		}

	}

}
