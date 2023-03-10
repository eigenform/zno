.PHONY: deploy
deploy: book
	git branch gh-pages
	git worktree add --force /tmp/book gh-pages
	mdbook clean
	mdbook build
	rm -rf /tmp/book/*
	cp -rp ./book/* /tmp/book
	pushd /tmp/book && \
		git update-ref -d refs/heads/gh-pages && \
		git add -A && \
		git commit -m "deploy mdbook to gh-pages" && \
		git push --force origin gh-pages && \
		popd
	git worktree remove /tmp/book
	git branch -D gh-pages
